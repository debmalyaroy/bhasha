"""Export encoder / decoder-step ONNX graphs (fp32) and int8 dynamically quantised copies.

  python -I export_onnx.py <model_dir> <out_dir>
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import numpy as np
import onnx
import torch
from onnxruntime.quantization import QuantType, quantize_dynamic

import it2

model_dir, out_dir = sys.argv[1], sys.argv[2]
os.makedirs(out_dir, exist_ok=True)
enc, dec, d = it2.build_torch(model_dir)
L, H, Dh = d["L"], d["H"], d["Dh"]

ids = torch.tensor([[5, 6, 7, 8, 9, 2]], dtype=torch.int64)
mask = torch.ones_like(ids)
with torch.no_grad():
    ck, cv = enc(ids, mask)
    pk = torch.zeros(L, 1, H, 3, Dh)
    pv = torch.zeros(L, 1, H, 3, Dh)

enc_fp = os.path.join(out_dir, "encoder.onnx")
dec_fp = os.path.join(out_dir, "decoder.onnx")
torch.onnx.export(enc, (ids, mask), enc_fp, dynamo=False, opset_version=17, do_constant_folding=True,
                  input_names=["input_ids", "attention_mask"], output_names=["cross_k", "cross_v"],
                  dynamic_axes={"input_ids": {0: "batch", 1: "src_len"}, "attention_mask": {0: "batch", 1: "src_len"},
                                "cross_k": {1: "batch", 3: "src_len"}, "cross_v": {1: "batch", 3: "src_len"}})
torch.onnx.export(dec, (torch.tensor([[2]]), mask, ck, cv, pk, pv), dec_fp, dynamo=False, opset_version=17, do_constant_folding=True,
                  input_names=["input_ids", "attention_mask", "cross_k", "cross_v", "past_k", "past_v"],
                  output_names=["logits", "present_k", "present_v"],
                  dynamic_axes={"input_ids": {0: "batch"}, "attention_mask": {0: "batch", 1: "src_len"},
                                "cross_k": {1: "batch", 3: "src_len"}, "cross_v": {1: "batch", 3: "src_len"},
                                "past_k": {1: "batch", 3: "past_len"}, "past_v": {1: "batch", 3: "past_len"},
                                "logits": {0: "batch"}, "present_k": {1: "batch", 3: "new_len"}, "present_v": {1: "batch", 3: "new_len"}})

for name in ("encoder", "decoder"):
    src = os.path.join(out_dir, name + ".onnx")
    dst = os.path.join(out_dir, name + ".int8.onnx")
    quantize_dynamic(src, dst, weight_type=QuantType.QInt8, op_types_to_quantize=["MatMul", "Gather"],
                     extra_options={"MatMulConstBOnly": True})
    m = onnx.load(dst, load_external_data=False)
    ops = {}
    for n in m.graph.node:
        ops[n.op_type] = ops.get(n.op_type, 0) + 1
    print(name, "fp32 MB", round(os.path.getsize(src) / 2 ** 20, 1), "int8 MB", round(os.path.getsize(dst) / 2 ** 20, 1))
    print("  int8 ops:", dict(sorted(ops.items(), key=lambda kv: -kv[1])))
    for t in list(m.graph.input) + list(m.graph.output):
        print("  ", t.name, onnx.helper.tensor_dtype_to_np_dtype(t.type.tensor_type.elem_type),
              [x.dim_param or x.dim_value for x in t.type.tensor_type.shape.dim])

# parity: torch vs fp32 ONNX vs int8 ONNX on a 3-step greedy rollout
tb = it2.TorchBackend.__new__(it2.TorchBackend)
tb.torch, tb.enc, tb.dec, tb.dims = torch, enc, dec, d
src = [5, 6, 700, 8000, 90, 12, 2]
ref = it2.greedy(tb, src, max_new=8)
for tag in ("", ".int8"):
    ob = it2.OrtBackend(os.path.join(out_dir, f"encoder{tag}.onnx"), os.path.join(out_dir, f"decoder{tag}.onnx"))
    i = np.array([src], dtype=np.int64)
    a = tb.encode(i, np.ones_like(i))[0]
    b = ob.encode(i, np.ones_like(i))[0]
    print(f"onnx{tag}: max |cross_k diff| {np.abs(a - b).max():.5f} (ref max {np.abs(a).max():.2f}); greedy ids equal to torch: {it2.greedy(ob, src, max_new=8) == ref}")
