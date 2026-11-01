# Paradee-8M v1.2 triage

`sahilmahendrakar/Paradee-8M-v1.2` at `ed2934d4df8a4fd891e29584c5e1104649409765`

| file | bytes | sha256 |
| --- | --- | --- |
| `config.json` | 1,827 | `002535cf8dd08a434cdddb13372da8a7b4844152bdf9b5b4e7342e12ec30bcfb` |
| `tokenizer.json` | 3,497 | `77a02c8e164413299b4b4c403b14f8e0e1c1b727db4d46a09d6327b861060a34` |
| `onnx/paradee.onnx` | 34,779,443 | `8bac7b0bebef403cb22b54ead6bee423fa34a04fd91f73326b7f65cd9d0d25b9` |
| `onnx/paradee_int8.onnx` | 8,462,119 | `a6989796692ac173597e15d405c971989202d5c73cfad65f22e90bd7b6c97da1` |
| `pytorch/text_side.pt` | 16,990,603 | `0e3e9ecacb6c4816baa1cdd3ed030e588f58ba7d0c6e5a5ad1b8c391e66973b9` |
| `pytorch/decoder.pt` | 14,655,977 | `f829c4c8fd191ea0cbadd41fdb27737a85f819669dcb9d0b666eea001c24d25c` |

| config key | value |
| --- | --- |
| `model_type` | `paradee` |
| `version` | `1.2` |
| `sample_rate` | `24000` |
| `n_token` | `178` |
| `voices` | `['af_heart', 'bf_isabella', 'am_puck', 'bm_fable']` |
| `teacher` | `hexgrad/Kokoro-82M` |
| `vocab` | `114 phoneme symbols` |

## `onnx/paradee.onnx`

- ir_version 8, producer pytorch 2.14.1, opset {'ai.onnx': 17}
- inputs: input_ids:INT64[1, 'tokens'], speed:FLOAT[1], voice:INT64[1]
- outputs: waveform:FLOAT['Addwaveform_dim_0', 'samples']
- 2117 nodes, 53 distinct operators
- initializer elements 8,581,449 by dtype {'float32': 8581449}
- operators the audio runtime does not implement: {'InstanceNormalization': 22, 'ConvTranspose': 7, 'LSTM': 6, 'Resize': 3}
- nondeterministic operators: {'RandomNormalLike': 1}
- full operator histogram: {'Constant': 726, 'Add': 196, 'Mul': 184, 'Unsqueeze': 93, 'Transpose': 86, 'Slice': 85, 'Reshape': 78, 'Gather': 77, 'MatMul': 67, 'Shape': 61, 'Concat': 60, 'Conv': 49, 'Div': 40, 'Identity': 30, 'Cast': 30, 'LayerNormalization': 25, 'Gemm': 25, 'LeakyRelu': 25, 'ConstantOfShape': 23, 'InstanceNormalization': 22, 'Where': 16, 'Clip': 10, 'Pow': 8, 'Tanh': 7, 'Sub': 7, 'ConvTranspose': 7, 'Pad': 7, 'Range': 6, 'Softmax': 6, 'IsNaN': 6, 'LSTM': 6, 'Erf': 5, 'Equal': 4, 'Expand': 4, 'Sin': 4, 'And': 3, 'Resize': 3, 'Exp': 3, 'Cos': 3, 'GreaterOrEqual': 2, 'ReduceSum': 2, 'CumSum': 2, 'Less': 2, 'Squeeze': 2, 'Floor': 2, 'GatherElements': 1, 'Flatten': 1, 'Sigmoid': 1, 'Round': 1, 'Sqrt': 1, 'Min': 1, 'Greater': 1, 'RandomNormalLike': 1}

## `onnx/paradee_int8.onnx`

- ir_version 8, producer pytorch 2.14.1, opset {'ai.onnx': 17}
- inputs: input_ids:INT64[1, 'tokens'], speed:FLOAT[1], voice:INT64[1]
- outputs: waveform:FLOAT['Addwaveform_dim_0', 'samples']
- 2535 nodes, 56 distinct operators
- initializer elements 7,867,227 by dtype {'int8': 7795388, 'float16': 66505, 'float32': 3072, 'int64': 2262}
- operators the audio runtime does not implement: {'InstanceNormalization': 22, 'ConvTranspose': 7, 'LSTM': 6, 'Resize': 3}
- nondeterministic operators: {'RandomNormalLike': 1}
- full operator histogram: {'Constant': 726, 'Cast': 303, 'Mul': 200, 'Add': 196, 'DequantizeLinear': 105, 'Unsqueeze': 97, 'Transpose': 86, 'Slice': 85, 'Reshape': 78, 'Gather': 77, 'MatMul': 67, 'Concat': 64, 'Shape': 61, 'Conv': 49, 'Div': 40, 'Identity': 30, 'LayerNormalization': 25, 'Gemm': 25, 'LeakyRelu': 25, 'ConstantOfShape': 23, 'InstanceNormalization': 22, 'Where': 16, 'Clip': 10, 'Sin': 8, 'Pow': 8, 'Cos': 7, 'Tanh': 7, 'Sub': 7, 'ConvTranspose': 7, 'Pad': 7, 'Range': 6, 'Softmax': 6, 'IsNaN': 6, 'LSTM': 6, 'Erf': 5, 'Mod': 4, 'Neg': 4, 'Equal': 4, 'Expand': 4, 'And': 3, 'Resize': 3, 'Exp': 3, 'GreaterOrEqual': 2, 'ReduceSum': 2, 'CumSum': 2, 'Less': 2, 'Squeeze': 2, 'Floor': 2, 'GatherElements': 1, 'Flatten': 1, 'Sigmoid': 1, 'Round': 1, 'Sqrt': 1, 'Min': 1, 'Greater': 1, 'RandomNormalLike': 1}
