import sherpa_onnx, numpy as np, json, wave
from scipy.signal import resample_poly
def tts(v):
    d=f"eval/voices/vits-piper-{v}"; name=v
    cfg=sherpa_onnx.OfflineTtsConfig(model=sherpa_onnx.OfflineTtsModelConfig(vits=sherpa_onnx.OfflineTtsVitsModelConfig(model=f"{d}/{name}.onnx",tokens=f"{d}/tokens.txt",data_dir=f"{d}/espeak-ng-data"),num_threads=4))
    return sherpa_onnx.OfflineTts(cfg)
V={"MOD":tts("en_GB-alan-medium"),"A":tts("en_US-ryan-medium"),"B":tts("en_US-amy-medium")}
script=[ # speaker, text, gap after (s)
("A","We have to renegotiate our trade deals, and we have to stop these countries from stealing our companies and our jobs.",0.12),
("A","Our manufacturing jobs have gone down by more than seventy thousand factories since China joined the World Trade Organization.",0.1),
("A","Unemployment in this country is far higher than the official number, and everybody knows it.",0.15),
("MOD","Secretary, would you like to respond?",0.15),
("B","Well, I think trade is an important issue. We are five percent of the world's population, so we have to trade with the other ninety five percent.",0.1),
("B","And the plan that has been put forth would be trickle-down economics all over again, with the biggest tax cuts for the top one percent we have ever had.",0.6),
("A","That is simply not true.",0.2),
("B","The economy added more than fourteen million private sector jobs over the last seven years.",0.15),
("MOD","Let's move to the next segment.",0.5),
("A","I will bring back jobs. You can't bring back jobs.",0.1),
]
out=[];truth=[];t=0.0;rng=np.random.default_rng(0)
for spk,text,gap in script:
    a=V[spk].generate(text,sid=0,speed=1.0); x=np.array(a.samples,dtype=np.float32)
    x=resample_poly(x,16000,a.sample_rate).astype(np.float32)
    truth.append({"speaker":spk,"start":round(t,2),"end":round(t+len(x)/16000,2),"text":text})
    out.append(x); t+=len(x)/16000
    out.append(np.zeros(int(gap*16000),dtype=np.float32)); t+=gap
y=np.concatenate(out); y=y/np.max(np.abs(y))*0.7 + rng.normal(0,0.003,len(y)).astype(np.float32)
pcm=(np.clip(y,-1,1)*32767).astype('<i2')
with wave.open("eval/debate.wav","wb") as w: w.setnchannels(1);w.setsampwidth(2);w.setframerate(16000);w.writeframes(pcm.tobytes())
json.dump(truth,open("eval/debate_truth.json","w"),indent=1)
for r in truth: print(r["speaker"],r["start"],r["end"],r["text"][:60])
print("total",round(t,1),"s")
