import {readFileSync} from 'node:fs';
export const catalog = JSON.parse(readFileSync(new URL('../../catalog/models.json', import.meta.url)));
export const models = ['s', 'm'].map(quant => catalog.models.find(m => m.id === `second_state_llava_v1_5_7b_gguf_q4_k_${quant}`));
export const report = `https://raw.githubusercontent.com/integrallis/models/${'e'.repeat(40)}/results/attempt.json`;
export function fixture() {
  return {schema:'llava-catalog-attempt-v1', receipt: {
    revision:'a'.repeat(40), threads:8, context:1024, maximumOutputTokens:32, kvType:'f32',
    corpusSha256:'b'.repeat(64), artifacts:Object.fromEntries(models.flatMap(m=>m.files.map(f=>[f.path,f.sha256]))),
  }, confirmationIntegration:{passed:true}, candidates:['Q4_K_S','Q4_K_M'].map(variant=>({
    variant, backendQualified:true, gates:{accuracyLoss:true,vqaFloor:true,textVqaFloor:true,
      imageContribution:true,tokenAgreement:true,performance:true,strongestReferencePerformance:true,integrationConfirmation:true},
    datasets:Object.fromEntries(['vqav2','textvqa'].map(d=>[d,{cases:32,blankCases:8,candidateScore:.75,referenceScore:.75,imageContribution:.2}])),
    tokenAgreement:1, performance:['vqav2','textvqa'].flatMap(dataset=>[0,1].map(i=>({id:`${dataset}-${i}`,dataset,
      warmups:1,repetitions:3,worstRatios:{prefillMs:1,decodeMs:1,totalMs:1},passed:true,generationLengthsMatch:true}))),
  }))};
}
export const bytes = value => Buffer.from(JSON.stringify(value));

