# Paradee-8M v1.1 triage

`sahilmahendrakar/Paradee-8M-v1.1` at `330617ad1f097b4f6b02c103e54b39006c090b45`

| file | bytes | sha256 |
| --- | --- | --- |
| `config.json` | 1,774 | `c3f420e8a5aea41b97f698404a1b1e28e403c662f65927345335fc5493f2e075` |
| `tokenizer.json` | 3,497 | `77a02c8e164413299b4b4c403b14f8e0e1c1b727db4d46a09d6327b861060a34` |
| `onnx/paradee.onnx` | 34,778,468 | `27f7893bc1773448ed449526700ed4c33050bfeeeecd51856bdd1a6927e3a7d7` |
| `onnx/paradee_int8.onnx` | 8,461,438 | `665c3cce6d9872e24bd43db4aa19d3b9c224cdeb6d79b0814190e1c2e43defbc` |
| `pytorch/text_side.pt` | 16,998,347 | `0995bee746043e4f06f72be68fb8634530b95d6467b4a141759e721122648062` |
| `pytorch/decoder.pt` | 14,655,657 | `143f9678a8cc2a53ca72e06a2f8a8dd67424a5395eb939c60ec820a1122c8a13` |

| config key | value |
| --- | --- |
| `model_type` | `paradee` |
| `version` | `1.1` |
| `sample_rate` | `24000` |
| `n_token` | `178` |
| `teacher` | `hexgrad/Kokoro-82M` |
| `teacher_voice` | `af_heart` |
| `vocab` | `114 phoneme symbols` |

## `onnx/paradee.onnx`

- ir_version 8, producer pytorch 2.14.0, opset {'ai.onnx': 17}
- inputs: input_ids:INT64[1, 'tokens'], speed:FLOAT[1]
- outputs: waveform:FLOAT['Addwaveform_dim_0', 'samples']
- 2113 nodes, 53 distinct operators
- initializer elements 8,581,337 by dtype {'float32': 8581337}
- operators the audio runtime does not implement: {'InstanceNormalization': 22, 'ConvTranspose': 7, 'LSTM': 6, 'Resize': 3}
- nondeterministic operators: {'RandomNormalLike': 1}
- full operator histogram: {'Constant': 725, 'Add': 196, 'Mul': 184, 'Unsqueeze': 92, 'Transpose': 86, 'Slice': 85, 'Reshape': 78, 'Gather': 75, 'MatMul': 67, 'Shape': 61, 'Concat': 60, 'Conv': 49, 'Div': 40, 'Identity': 30, 'Cast': 30, 'LayerNormalization': 25, 'Gemm': 25, 'LeakyRelu': 25, 'ConstantOfShape': 23, 'InstanceNormalization': 22, 'Where': 16, 'Clip': 10, 'Pow': 8, 'Tanh': 7, 'Sub': 7, 'ConvTranspose': 7, 'Pad': 7, 'Range': 6, 'Softmax': 6, 'IsNaN': 6, 'LSTM': 6, 'Erf': 5, 'Equal': 4, 'Expand': 4, 'Sin': 4, 'And': 3, 'Resize': 3, 'Exp': 3, 'Cos': 3, 'GreaterOrEqual': 2, 'ReduceSum': 2, 'CumSum': 2, 'Less': 2, 'Squeeze': 2, 'Floor': 2, 'GatherElements': 1, 'Flatten': 1, 'Sigmoid': 1, 'Round': 1, 'Sqrt': 1, 'Min': 1, 'Greater': 1, 'RandomNormalLike': 1}

## `onnx/paradee_int8.onnx`

- ir_version 8, producer pytorch 2.14.0, opset {'ai.onnx': 17}
- inputs: input_ids:INT64[1, 'tokens'], speed:FLOAT[1]
- outputs: waveform:FLOAT['Addwaveform_dim_0', 'samples']
- 2529 nodes, 56 distinct operators
- initializer elements 7,867,115 by dtype {'int8': 7795388, 'float16': 66313, 'float32': 3152, 'int64': 2262}
- operators the audio runtime does not implement: {'InstanceNormalization': 22, 'ConvTranspose': 7, 'LSTM': 6, 'Resize': 3}
- nondeterministic operators: {'RandomNormalLike': 1}
- full operator histogram: {'Constant': 725, 'Cast': 301, 'Mul': 200, 'Add': 196, 'DequantizeLinear': 105, 'Unsqueeze': 96, 'Transpose': 86, 'Slice': 85, 'Reshape': 78, 'Gather': 75, 'MatMul': 67, 'Concat': 64, 'Shape': 61, 'Conv': 49, 'Div': 40, 'Identity': 30, 'LayerNormalization': 25, 'Gemm': 25, 'LeakyRelu': 25, 'ConstantOfShape': 23, 'InstanceNormalization': 22, 'Where': 16, 'Clip': 10, 'Sin': 8, 'Pow': 8, 'Cos': 7, 'Tanh': 7, 'Sub': 7, 'ConvTranspose': 7, 'Pad': 7, 'Range': 6, 'Softmax': 6, 'IsNaN': 6, 'LSTM': 6, 'Erf': 5, 'Mod': 4, 'Neg': 4, 'Equal': 4, 'Expand': 4, 'And': 3, 'Resize': 3, 'Exp': 3, 'GreaterOrEqual': 2, 'ReduceSum': 2, 'CumSum': 2, 'Less': 2, 'Squeeze': 2, 'Floor': 2, 'GatherElements': 1, 'Flatten': 1, 'Sigmoid': 1, 'Round': 1, 'Sqrt': 1, 'Min': 1, 'Greater': 1, 'RandomNormalLike': 1}
