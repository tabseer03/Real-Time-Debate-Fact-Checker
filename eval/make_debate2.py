import sherpa_onnx, numpy as np, json, wave
from scipy.signal import resample_poly, butter, sosfilt
def tts(v):
    d=f"eval/voices/vits-piper-{v}"
    return sherpa_onnx.OfflineTts(sherpa_onnx.OfflineTtsConfig(model=sherpa_onnx.OfflineTtsModelConfig(vits=sherpa_onnx.OfflineTtsVitsModelConfig(model=f"{d}/{v}.onnx",tokens=f"{d}/tokens.txt",data_dir=f"{d}/espeak-ng-data"),num_threads=4)))
V={"MOD":tts("en_GB-alan-medium"),"A":tts("en_US-ryan-medium"),"B":tts("en_US-amy-medium")}
script=[
("B","The economy added fourteen million private sector jobs, and unemployment is down to five percent.",0.15),
("MOD","Again.",0.1),
("MOD","Let me follow up with you. How will you bring jobs back from overseas?",0.12),
("A","Well.",0.25),
("A","For one thing, my father gave me a very small loan in nineteen seventy five, and I built it into a company worth many billions of dollars.",0.1),
("B","Yes.",0.1),
("A","Is that okay? Good. Our country is in deep trouble. China is devaluing its currency and they are the best ever at it.",0.1),
("MOD","Let me interrupt.",0.1),
("A","Mexico has a value added tax of about sixteen percent. That is a defective agreement.",0.15),
("B","That is not true.",0.1),
("MOD","Secretary, your response.",0.15),
("B","Look, we need smart, fair trade deals, and a tax system that rewards work, not just financial transactions.",0.4),
]
out=[];truth=[];t=0.0;rng=np.random.default_rng(3)
for spk,text,gap in script:
    a=V[spk].generate(text,sid=0,speed=1.05); x=resample_poly(np.array(a.samples,dtype=np.float32),16000,a.sample_rate).astype(np.float32)
    x=x/np.max(np.abs(x))*rng.uniform(0.5,0.9)
    truth.append({"speaker":spk,"start":round(t,2),"end":round(t+len(x)/16000,2),"text":text}); out.append(x); t+=len(x)/16000
    out.append(np.zeros(int(gap*16000),dtype=np.float32)); t+=gap
y=np.concatenate(out)
y=sosfilt(butter(4,[200,3800],btype='band',fs=16000,output='sos'),y)          # TV/phone band
y=np.tanh(2.5*y)/np.tanh(2.5)                                                  # broadcast compression
y=y+rng.normal(0,0.01,len(y))+0.02*np.sin(2*np.pi*120*np.arange(len(y))/16000)*rng.normal(1,0.3,len(y))  # hiss + hum/crowd
y=(y/np.max(np.abs(y))*0.8).astype(np.float32)
with wave.open("eval/debate2.wav","wb") as w: w.setnchannels(1);w.setsampwidth(2);w.setframerate(16000);w.writeframes((y*32767).astype('<i2').tobytes())
json.dump(truth,open("eval/debate2_truth.json","w"),indent=1)
for r in truth: print(f'{r["speaker"]:4s}{r["start"]:6.1f}-{r["end"]:5.1f} {r["text"][:70]}')
