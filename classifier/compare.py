"""Compare features/models for sentence classification on ClaimBuster.

Labels: -1 NFS (non-factual), 0 UFS (unimportant factual), 1 CFS (check-worthy factual).
Test set = the three 2016 debates (2016-09-26 is our live test recording), never trained on.
Run from the project root: python classifier/compare.py
"""
import os
import time

import numpy as np
import pandas as pd
from sklearn.feature_extraction.text import TfidfVectorizer
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import classification_report, f1_score
from xgboost import XGBClassifier

DATA = "classifier/data"
EMBED_MODEL = "sentence-transformers/all-MiniLM-L6-v2"
NAMES = ["NFS", "UFS", "CFS"]

df = pd.concat([pd.read_csv(f"{DATA}/crowdsourced.csv"), pd.read_csv(f"{DATA}/groundtruth.csv")])
df = df.drop_duplicates("Sentence_id").reset_index(drop=True)
df["y"] = df["Verdict"] + 1
test = df["File_id"].str.startswith("2016").to_numpy()
y_train, y_test = df["y"][~test].to_numpy(), df["y"][test].to_numpy()
print(f"train {(~test).sum()}  test {test.sum()} (2016 debates)")
print("test label counts:", dict(zip(NAMES, np.bincount(y_test))))


def embeddings():
    path = f"{DATA}/minilm.npy"
    if os.path.exists(path):
        return np.load(path)
    from sentence_transformers import SentenceTransformer
    model = SentenceTransformer(EMBED_MODEL)
    emb = model.encode(df["Text"].tolist(), batch_size=128, normalize_embeddings=True,
                       show_progress_bar=True)
    np.save(path, emb)
    return emb


def tfidf():
    vec = TfidfVectorizer(ngram_range=(1, 2), min_df=2, sublinear_tf=True)
    return vec.fit_transform(df["Text"][~test]), vec.transform(df["Text"][test])


def xgb():
    return XGBClassifier(n_estimators=400, max_depth=6, learning_rate=0.1, subsample=0.8,
                         colsample_bytree=0.5, tree_method="hist", n_jobs=6)


def logreg():
    return LogisticRegression(C=10, max_iter=2000, class_weight="balanced")


emb = embeddings()
tf_train, tf_test = tfidf()
features = {
    "tfidf": (tf_train, tf_test),
    "minilm": (emb[~test], emb[test]),
}

rows = []
for fname, (x_train, x_test) in features.items():
    for mname, make in (("xgboost", xgb), ("logreg", logreg)):
        model = make()
        t0 = time.time()
        model.fit(x_train, y_train)
        fit_s = time.time() - t0
        pred = model.predict(x_test)
        rep = classification_report(y_test, pred, target_names=NAMES, output_dict=True,
                                    zero_division=0)
        rows.append({
            "features": fname, "model": mname,
            "macro_f1": f1_score(y_test, pred, average="macro"),
            "accuracy": rep["accuracy"],
            "CFS_prec": rep["CFS"]["precision"], "CFS_rec": rep["CFS"]["recall"],
            "UFS_f1": rep["UFS"]["f1-score"], "NFS_f1": rep["NFS"]["f1-score"],
            "fit_s": fit_s,
        })
        print(f"\n== {fname} + {mname} ==")
        print(classification_report(y_test, pred, target_names=NAMES, zero_division=0))

print(pd.DataFrame(rows).round(3).to_string(index=False))
