"""Fine-tune a small transformer on ClaimBuster as the gate in front of the LLM.

Labels: 0 NFS (non-factual), 1 UFS (unimportant factual), 2 CFS (check-worthy factual).
A sentence passes the gate when P(UFS) + P(CFS) >= threshold.
Split by debate: 2016 = test (2016-09-26 is our live recording), 2012 = dev, the rest = train.
The epoch and the threshold are chosen on dev (fewest sentences passed while keeping
TARGET_RECALL of the CFS sentences); test is only reported.
Run from the project root: classifier/.venv/Scripts/python classifier/finetune.py [model-name]
"""
import json
import sys
import time

import numpy as np
import pandas as pd
import torch
from sklearn.metrics import classification_report
from transformers import AutoModelForSequenceClassification, AutoTokenizer

DATA = "classifier/data"
OUT = "classifier/model"
MODEL = sys.argv[1] if len(sys.argv) > 1 else "sentence-transformers/all-MiniLM-L6-v2"
NAMES = ["NFS", "UFS", "CFS"]
EPOCHS, BATCH, LR, MAX_LEN = 4, 32, 5e-5, 64
TARGET_RECALL = 0.95

df = pd.concat([pd.read_csv(f"{DATA}/crowdsourced.csv"), pd.read_csv(f"{DATA}/groundtruth.csv")])
df = df.drop_duplicates("Sentence_id").reset_index(drop=True)
df["y"] = df["Verdict"] + 1
year = df["File_id"].str[:4]
splits = {"train": (year != "2016") & (year != "2012"), "dev": year == "2012", "test": year == "2016"}
text = {k: df["Text"][m].tolist() for k, m in splits.items()}
y = {k: df["y"][m].to_numpy() for k, m in splits.items()}
print({k: len(v) for k, v in text.items()})

device = "cuda" if torch.cuda.is_available() else "cpu"
print(f"fine-tuning {MODEL} on {device}")
torch.manual_seed(0)
tok = AutoTokenizer.from_pretrained(MODEL)
model = AutoModelForSequenceClassification.from_pretrained(MODEL, num_labels=3).to(device)


def batches(texts, size):
    for i in range(0, len(texts), size):
        enc = tok(texts[i:i + size], padding=True, truncation=True, max_length=MAX_LEN,
                  return_tensors="pt")
        yield i, {k: v.to(device) for k, v in enc.items()}


def predict(texts):
    model.eval()
    out = []
    with torch.no_grad():
        for _, enc in batches(texts, 128):
            out.append(torch.softmax(model(**enc).logits, -1).cpu().numpy())
    return np.concatenate(out)


def threshold_for(proba, labels, recall):
    """Highest threshold on P(factual) that still passes `recall` of the CFS sentences."""
    cfs = np.sort(1 - proba[labels == 2, 0])
    return float(cfs[int(np.floor((1 - recall) * len(cfs)))])


def gate(proba, labels, t):
    passed = 1 - proba[:, 0] >= t
    return {"passed": passed.mean(), "cfs_kept": passed[labels == 2].mean(),
            "factual_kept": passed[labels > 0].mean(), "precision": (labels > 0)[passed].mean()}


counts = np.bincount(y["train"])
weights = torch.tensor((counts.sum() / counts) ** 0.5, dtype=torch.float, device=device)
loss_fn = torch.nn.CrossEntropyLoss(weight=weights / weights.mean())
opt = torch.optim.AdamW(model.parameters(), lr=LR, weight_decay=0.01)
steps = EPOCHS * (len(text["train"]) // BATCH + 1)
sched = torch.optim.lr_scheduler.OneCycleLR(opt, max_lr=LR, total_steps=steps, pct_start=0.1,
                                            anneal_strategy="linear")
rng = np.random.default_rng(0)
best = None
t0 = time.time()
for epoch in range(1, EPOCHS + 1):
    model.train()
    order = rng.permutation(len(text["train"]))
    texts = [text["train"][i] for i in order]
    labels = torch.tensor(y["train"][order], device=device)
    total = 0.0
    for i, enc in batches(texts, BATCH):
        loss = loss_fn(model(**enc).logits, labels[i:i + BATCH])
        loss.backward()
        torch.nn.utils.clip_grad_norm_(model.parameters(), 1.0)
        opt.step()
        sched.step()
        opt.zero_grad()
        total += loss.item()
    dev = predict(text["dev"])
    t = threshold_for(dev, y["dev"], TARGET_RECALL)
    g = gate(dev, y["dev"], t)
    print(f"epoch {epoch}: loss {total / (len(texts) / BATCH):.3f}  dev acc "
          f"{(dev.argmax(1) == y['dev']).mean():.3f}  dev gate at {TARGET_RECALL:.0%} CFS kept: "
          f"threshold {t:.4f}, passes {g['passed']:.1%}  ({time.time() - t0:.0f}s)")
    if best is None or g["passed"] < best["passed"]:
        best = {"epoch": epoch, "threshold": t, "passed": g["passed"]}
        model.save_pretrained(OUT)
        tok.save_pretrained(OUT)

print(f"\nkept epoch {best['epoch']}, threshold {best['threshold']:.4f}")
model = AutoModelForSequenceClassification.from_pretrained(OUT).to(device)
test = predict(text["test"])
print(classification_report(y["test"], test.argmax(1), target_names=NAMES, zero_division=0))
print("test gate:  threshold  passed%  CFS kept%  factual kept%  precision%")
dev = predict(text["dev"])
for recall in (0.90, 0.95, 0.98):
    t = threshold_for(dev, y["dev"], recall)
    g = gate(test, y["test"], t)
    print(f"  dev {recall:.0%}:   {t:.4f}    {100 * g['passed']:5.1f}    {100 * g['cfs_kept']:5.1f}"
          f"       {100 * g['factual_kept']:5.1f}        {100 * g['precision']:5.1f}")

with open(f"{OUT}/gate.json", "w") as f:
    json.dump({"labels": NAMES, "threshold": best["threshold"], "target_cfs_recall": TARGET_RECALL,
               "max_length": MAX_LEN, "base_model": MODEL}, f, indent=1)

model.to("cpu")
one = tok(["We have to renegotiate our trade deals."], return_tensors="pt")
with torch.no_grad():
    model(**one)
    t0 = time.time()
    for _ in range(50):
        model(**one)
print(f"\nCPU inference (PyTorch, 1 sentence): {(time.time() - t0) * 20:.1f} ms")
