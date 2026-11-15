"""Self-contained RoPE-IndicTrans2 inference: tokenizer, model (PyTorch), search, ONNX Runtime backend.

Nothing from the downloaded repository is executed: weights are read with torch.load(weights_only=True),
vocabularies are JSON, SentencePiece models are loaded by the sentencepiece library.
"""
import json
import math
import os

import numpy as np

BOS, PAD, EOS, UNK = 0, 1, 2, 3
MAX_POS = 1024  # rotary table length baked into the exported graphs


# ----------------------------------------------------------------------------- tokenizer
class Tokenizer:
    def __init__(self, model_dir):
        import sentencepiece as spm
        self.src = json.load(open(os.path.join(model_dir, "dict.SRC.json"), encoding="utf-8"))
        tgt = json.load(open(os.path.join(model_dir, "dict.TGT.json"), encoding="utf-8"))
        self.tgt = {v: k for k, v in tgt.items()}
        self.spm = spm.SentencePieceProcessor(model_file=os.path.join(model_dir, "model.SRC"))

    def pieces(self, tagged):
        """tagged: '<src_tag> <tgt_tag> <preprocessed text>'"""
        src_tag, tgt_tag, text = tagged.split(" ", 2)
        return [src_tag, tgt_tag] + self.spm.EncodeAsPieces(text)

    def encode(self, tagged):
        p = self.pieces(tagged)
        return p, [self.src.get(x, UNK) for x in p] + [EOS]

    def decode(self, ids):
        toks = [self.tgt.get(i, "<unk>") for i in ids if i not in (BOS, PAD, EOS, UNK)]
        s = "".join(toks).replace("▁", " ").strip()
        # transformers' clean_up_tokenization (clean_up_tokenization_spaces=True)
        for a, b in ((" .", "."), (" ?", "?"), (" !", "!"), (" ,", ","), (" ' ", "'"), (" n't", "n't"),
                     (" 'm", "'m"), (" 's", "'s"), (" 've", "'ve"), (" 're", "'re")):
            s = s.replace(a, b)
        return s


# ----------------------------------------------------------------------------- torch model
def build_torch(model_dir):
    import torch
    import torch.nn as nn
    import torch.nn.functional as F

    cfg = json.load(open(os.path.join(model_dir, "config.json"), encoding="utf-8"))
    sd = torch.load(os.path.join(model_dir, "pytorch_model.bin"), map_location="cpu", weights_only=True)
    D, H = cfg["encoder_embed_dim"], cfg["encoder_attention_heads"]
    L_enc, L_dec = cfg["encoder_layers"], cfg["decoder_layers"]
    Dh = D // H
    R = Dh // 2  # rotated dims per head ("partial rotation")
    theta = cfg["rope_args"].get("theta", 10000)
    assert cfg["decoder_embed_dim"] == D and cfg["decoder_attention_heads"] == H
    assert cfg["encoder_normalize_before"] and cfg["decoder_normalize_before"]
    assert cfg["activation_function"] == "gelu" and cfg["scale_embedding"]

    inv_freq = 1.0 / (theta ** (torch.arange(0, R, 2).float() / R))
    ang = torch.arange(MAX_POS).float()[:, None] * inv_freq[None, :]
    ang = ang.repeat_interleave(2, dim=-1)  # [MAX_POS, R]: f0,f0,f1,f1,...
    COS, SIN = ang.cos(), ang.sin()

    def rotate(t, cos, sin):
        """t: [B,H,T,Dh]; cos/sin: [T,R]. Rotates the first R dims of each head, leaves the rest."""
        left, right = t[..., :R], t[..., R:]
        pairs = left.reshape(left.shape[0], H, -1, R // 2, 2)
        rot = torch.stack((-pairs[..., 1], pairs[..., 0]), dim=-1).reshape(left.shape[0], H, -1, R)
        return torch.cat((left * cos + rot * sin, right), dim=-1)

    def lin(prefix):
        w, b = sd[prefix + ".weight"], sd[prefix + ".bias"]
        m = nn.Linear(w.shape[1], w.shape[0])
        m.weight.data, m.bias.data = w, b
        return m

    def norm(prefix):
        m = nn.LayerNorm(D)
        m.weight.data, m.bias.data = sd[prefix + ".weight"], sd[prefix + ".bias"]
        return m

    def heads(x):  # [B,T,D] -> [B,H,T,Dh]
        return x.reshape(x.shape[0], -1, H, Dh).transpose(1, 2)

    def merge(x):  # [B,H,T,Dh] -> [B,T,D]
        return x.transpose(1, 2).reshape(x.shape[0], -1, D)

    class Attn(nn.Module):
        def __init__(self, p):
            super().__init__()
            self.q, self.k, self.v, self.o = lin(p + ".q_proj"), lin(p + ".k_proj"), lin(p + ".v_proj"), lin(p + ".out_proj")

    class EncLayer(nn.Module):
        def __init__(self, p):
            super().__init__()
            self.attn, self.n1, self.n2 = Attn(p + ".self_attn"), norm(p + ".self_attn_layer_norm"), norm(p + ".final_layer_norm")
            self.fc1, self.fc2 = lin(p + ".fc1"), lin(p + ".fc2")

        def forward(self, x, mask, cos, sin):
            h = self.n1(x)
            q = rotate(heads(self.attn.q(h)) * (Dh ** -0.5), cos, sin)
            k = rotate(heads(self.attn.k(h)), cos, sin)
            a = torch.softmax(torch.matmul(q, k.transpose(-1, -2)) + mask, dim=-1)
            x = x + self.attn.o(merge(torch.matmul(a, heads(self.attn.v(h)))))
            return x + self.fc2(F.gelu(self.fc1(self.n2(x))))

    class DecLayer(nn.Module):
        def __init__(self, p):
            super().__init__()
            self.sa, self.ca = Attn(p + ".self_attn"), Attn(p + ".encoder_attn")
            self.n1, self.n2, self.n3 = norm(p + ".self_attn_layer_norm"), norm(p + ".encoder_attn_layer_norm"), norm(p + ".final_layer_norm")
            self.fc1, self.fc2 = lin(p + ".fc1"), lin(p + ".fc2")

        def forward(self, x, mask, ck, cv, pk, pv, cos, sin):
            h = self.n1(x)
            q = rotate(heads(self.sa.q(h)) * (Dh ** -0.5), cos, sin)
            k = torch.cat((pk, rotate(heads(self.sa.k(h)), cos, sin)), dim=2)  # cache holds already-rotated keys
            v = torch.cat((pv, heads(self.sa.v(h))), dim=2)
            a = torch.softmax(torch.matmul(q, k.transpose(-1, -2)), dim=-1)
            x = x + self.sa.o(merge(torch.matmul(a, v)))
            h = self.n2(x)
            q = heads(self.ca.q(h)) * (Dh ** -0.5)
            a = torch.softmax(torch.matmul(q, ck.transpose(-1, -2)) + mask, dim=-1)
            x = x + self.ca.o(merge(torch.matmul(a, cv)))
            return x + self.fc2(F.gelu(self.fc1(self.n3(x)))), k, v

    dec_layers = nn.ModuleList([DecLayer(f"model.decoder.layers.{i}") for i in range(L_dec)])

    class Encoder(nn.Module):
        """input_ids [B,S] int64, attention_mask [B,S] int64 -> cross_k, cross_v [L_dec,B,H,S,Dh]"""

        def __init__(self):
            super().__init__()
            self.emb = nn.Embedding.from_pretrained(sd["model.encoder.embed_tokens.weight"])
            self.ne = norm("model.encoder.layernorm_embedding") if cfg["layernorm_embedding"] else nn.Identity()
            self.nf = norm("model.encoder.layer_norm")
            self.layers = nn.ModuleList([EncLayer(f"model.encoder.layers.{i}") for i in range(L_enc)])
            self.ck = nn.ModuleList([l.ca.k for l in dec_layers])
            self.cv = nn.ModuleList([l.ca.v for l in dec_layers])
            self.register_buffer("cos", COS, persistent=False)
            self.register_buffer("sin", SIN, persistent=False)

        def forward(self, input_ids, attention_mask):
            S = input_ids.shape[1]
            cos, sin = self.cos[:S], self.sin[:S]
            mask = (1.0 - attention_mask[:, None, None, :].to(torch.float32)) * -1e9
            x = self.ne(self.emb(input_ids) * math.sqrt(D))
            for l in self.layers:
                x = l(x, mask, cos, sin)
            x = self.nf(x)
            return (torch.stack([heads(k(x)) for k in self.ck]), torch.stack([heads(v(x)) for v in self.cv]))

    class Decoder(nn.Module):
        """One step. input_ids [B,1]; attention_mask [B,S]; cross_k/v [L,B,H,S,Dh]; past_k/v [L,B,H,T,Dh] (T may be 0)
        -> logits [B,V], present_k/v [L,B,H,T+1,Dh]"""

        def __init__(self):
            super().__init__()
            self.emb = nn.Embedding.from_pretrained(sd["model.decoder.embed_tokens.weight"])
            self.ne = norm("model.decoder.layernorm_embedding") if cfg["layernorm_embedding"] else nn.Identity()
            self.nf = norm("model.decoder.layer_norm")
            self.layers = dec_layers
            self.out = nn.Linear(D, self.emb.weight.shape[0], bias=False)
            self.out.weight.data = sd["lm_head.weight"]
            self.register_buffer("cos", COS, persistent=False)
            self.register_buffer("sin", SIN, persistent=False)

        def forward(self, input_ids, attention_mask, cross_k, cross_v, past_k, past_v):
            T = past_k.shape[3]
            cos, sin = self.cos[T:T + 1], self.sin[T:T + 1]
            mask = (1.0 - attention_mask[:, None, None, :].to(torch.float32)) * -1e9
            x = self.ne(self.emb(input_ids) * math.sqrt(D))
            ks, vs = [], []
            for i, l in enumerate(self.layers):
                x, k, v = l(x, mask, cross_k[i], cross_v[i], past_k[i], past_v[i], cos, sin)
                ks.append(k)
                vs.append(v)
            return self.out(self.nf(x))[:, 0, :], torch.stack(ks), torch.stack(vs)

    return Encoder().eval(), Decoder().eval(), {"D": D, "H": H, "Dh": Dh, "L": L_dec, "V": sd["lm_head.weight"].shape[0]}


class TorchBackend:
    def __init__(self, model_dir, threads=4):
        import torch
        torch.set_num_threads(threads)
        self.torch = torch
        self.enc, self.dec, self.dims = build_torch(model_dir)

    def encode(self, ids, mask):
        with self.torch.inference_mode():
            k, v = self.enc(self.torch.from_numpy(ids), self.torch.from_numpy(mask))
        return k.numpy(), v.numpy()

    def step(self, tok, mask, ck, cv, pk, pv):
        t = self.torch
        with t.inference_mode():
            lg, k, v = self.dec(t.from_numpy(tok), t.from_numpy(mask), t.from_numpy(ck), t.from_numpy(cv), t.from_numpy(pk), t.from_numpy(pv))
        return lg.numpy(), k.numpy(), v.numpy()


class OrtBackend:
    def __init__(self, encoder_path, decoder_path, threads=4):
        import onnxruntime as ort
        so = ort.SessionOptions()
        so.intra_op_num_threads = threads
        so.inter_op_num_threads = 1
        self.enc = ort.InferenceSession(encoder_path, so, providers=["CPUExecutionProvider"])
        self.dec = ort.InferenceSession(decoder_path, so, providers=["CPUExecutionProvider"])
        shp = self.dec.get_inputs()[4].shape  # past_k [L,B,H,T,Dh]
        self.dims = {"L": shp[0], "H": shp[2], "Dh": shp[4]}

    def encode(self, ids, mask):
        return self.enc.run(None, {"input_ids": ids, "attention_mask": mask})

    def step(self, tok, mask, ck, cv, pk, pv):
        return self.dec.run(None, {"input_ids": tok, "attention_mask": mask, "cross_k": ck, "cross_v": cv, "past_k": pk, "past_v": pv})


# ----------------------------------------------------------------------------- search
def log_softmax(x):
    x = x - x.max(axis=-1, keepdims=True)
    return x - np.log(np.exp(x).sum(axis=-1, keepdims=True))


def greedy(be, src_ids, max_new=256):
    ids = np.array([src_ids], dtype=np.int64)
    mask = np.ones_like(ids)
    ck, cv = be.encode(ids, mask)
    d = be.dims
    pk = np.zeros((d["L"], 1, d["H"], 0, d["Dh"]), dtype=np.float32)
    pv = pk.copy()
    tok, out = EOS, []
    for _ in range(max_new):
        logits, pk, pv = be.step(np.array([[tok]], dtype=np.int64), mask, ck, cv, pk, pv)
        tok = int(logits[0].argmax())
        if tok == EOS:
            break
        out.append(tok)
    return out


def beam(be, src_ids, n=5, max_new=256, length_penalty=1.0):
    """Plain beam search; hypotheses are ranked by sum(log p) / (generated length incl. EOS) ** length_penalty."""
    ids = np.array([src_ids], dtype=np.int64)
    mask1 = np.ones_like(ids)
    ck1, cv1 = be.encode(ids, mask1)
    d = be.dims
    pk = np.zeros((d["L"], 1, d["H"], 0, d["Dh"]), dtype=np.float32)
    pv = pk.copy()
    hyps, scores, done = [[]], np.zeros(1), []  # done: (normalised score, tokens)
    for t in range(max_new):
        B = len(hyps)
        last = np.array([[h[-1] if h else EOS] for h in hyps], dtype=np.int64)
        logits, pk, pv = be.step(last, np.repeat(mask1, B, 0), np.repeat(ck1, B, 1), np.repeat(cv1, B, 1), pk, pv)
        lp = log_softmax(logits.astype(np.float64)) + scores[:, None]
        V = lp.shape[1]
        flat = lp.reshape(-1)
        top = np.argpartition(-flat, 2 * n)[:2 * n]
        top = top[np.argsort(-flat[top], kind="stable")]
        new_h, new_s, src = [], [], []
        for i in top:
            b, w = divmod(int(i), V)
            if w == EOS:
                done.append((flat[i] / (len(hyps[b]) + 1) ** length_penalty, hyps[b]))
            else:
                new_h.append(hyps[b] + [w]); new_s.append(flat[i]); src.append(b)
            if len(new_h) == n:
                break
        done.sort(key=lambda x: -x[0])
        done = done[:n]
        # stop when n hypotheses are finished and the best open one cannot beat the worst of them
        if len(done) >= n and new_s[0] / (t + 2) ** length_penalty <= done[-1][0]:
            break
        hyps, scores = new_h, np.array(new_s)
        pk, pv = pk[:, src], pv[:, src]
    if not done:
        return hyps[0]
    return max(done, key=lambda x: x[0])[1]
