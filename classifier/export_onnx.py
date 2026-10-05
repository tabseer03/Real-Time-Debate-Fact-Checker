"""Export the fine-tuned gate model for the Java backend and check it against PyTorch.

Run from the project root: classifier/.venv/Scripts/python classifier/export_onnx.py
Writes models/claim-gate/: gate.onnx (inputs input_ids, attention_mask, token_type_ids; output
logits), vocab.txt, gate.json (threshold). Also writes classifier/data/parity.tsv — the token ids
and P(factual) Python gets for every 2016 test sentence — for claims.GateCheck to compare against.
"""
import os
import shutil
import time

import numpy as np
import onnxruntime as ort
import pandas as pd
import torch
from transformers import AutoModelForSequenceClassification, AutoTokenizer

SRC = "classifier/model"
OUT = "models/claim-gate"
DATA = "classifier/data"
MAX_LEN = 64
NAMES = ["input_ids", "attention_mask", "token_type_ids"]

os.makedirs(OUT, exist_ok=True)
tok = AutoTokenizer.from_pretrained(SRC)
model = AutoModelForSequenceClassification.from_pretrained(SRC).eval()
model.config.return_dict = False
enc = tok(["We have to renegotiate our trade deals.", "Thank you."], padding=True,
          return_tensors="pt")
axes = {n: {0: "batch", 1: "tokens"} for n in NAMES} | {"logits": {0: "batch"}}
torch.onnx.export(model, tuple(enc[n] for n in NAMES), f"{OUT}/gate.onnx", input_names=NAMES,
                  output_names=["logits"], dynamic_axes=axes, opset_version=17, dynamo=False)
with open(f"{OUT}/vocab.txt", "w", encoding="utf-8", newline="\n") as f:
    for token, _ in sorted(tok.get_vocab().items(), key=lambda kv: kv[1]):
        f.write(token + "\n")
shutil.copy(f"{SRC}/gate.json", f"{OUT}/gate.json")

sess = ort.InferenceSession(f"{OUT}/gate.onnx", providers=["CPUExecutionProvider"])
with torch.no_grad():
    want = model(**enc)[0].numpy()
got = sess.run(None, {n: enc[n].numpy() for n in NAMES})[0]
print("max logit difference vs PyTorch:", float(np.abs(want - got).max()))

df = pd.concat([pd.read_csv(f"{DATA}/crowdsourced.csv"), pd.read_csv(f"{DATA}/groundtruth.csv")])
df = df.drop_duplicates("Sentence_id")
df = df[df["File_id"].str.startswith("2016")]
t0 = time.time()
with open(f"{DATA}/parity.tsv", "w", encoding="utf-8") as f:
    for text, verdict in zip(df["Text"], df["Verdict"]):
        one = tok([text], truncation=True, max_length=MAX_LEN, return_tensors="np")
        logits = sess.run(None, {n: one[n] for n in NAMES})[0][0].astype(np.float64)
        p = np.exp(logits - logits.max())
        p /= p.sum()
        ids = " ".join(map(str, one["input_ids"][0]))
        clean = text.replace("\t", " ").replace("\n", " ").replace("\r", " ")
        f.write(f"{verdict + 1}\t{1 - p[0]:.6f}\t{ids}\t{clean}\n")
print(f"wrote {len(df)} sentences to {DATA}/parity.tsv "
      f"({(time.time() - t0) * 1000 / len(df):.1f} ms each incl. tokenizing)")
