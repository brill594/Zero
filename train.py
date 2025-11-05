# -*- coding: utf-8 -*-
import os, re, unicodedata, csv
from types import SimpleNamespace
import pandas as pd
import tensorflow as tf

os.environ.setdefault("TF_CPP_MIN_LOG_LEVEL", "2")
for g in tf.config.list_physical_devices("GPU"):
    try: tf.config.experimental.set_memory_growth(g, True)
    except: pass

print("TensorFlow:", tf.__version__)
print("GPUs:", tf.config.list_physical_devices("GPU"))

# ========== 预处理（中文→ASCII） ==========
# 优先用拼音，失败则回退到“码点 token”
try:
    from pypinyin import pinyin, Style
    USE_PINYIN = True
except Exception:
    USE_PINYIN = False

_url = re.compile(r"https?://\S+|www\.\S+")
_email = re.compile(r"[\w\.-]+@[\w\.-]+\.\w+")
_phone = re.compile(r"(?:\+?\d[\d\- ]{6,}\d)")
_code  = re.compile(r"\b\d{4,8}\b")
_track = re.compile(r"\b(?:SF|YT|ZTO|JD)\w{6,}\b", re.I)
_zhblk  = re.compile(r"[\u4E00-\u9FFF]+")

def zh_to_pinyin_block(m):
    han = m.group(0)
    syls = pinyin(han, style=Style.NORMAL, errors='ignore')  # 无声调，ASCII
    return "'".join(s[0] for s in syls if s and s[0])

def zh_to_codepoints_block(m):
    return " ".join(f"U{ord(ch):04X}" for ch in m.group(0))

def preprocess_for_awe(text: str) -> str:
    """训练 & 线上统一用。把中文转成 ASCII token（拼音或码点）"""
    if not isinstance(text, str): text = str(text)
    t = unicodedata.normalize("NFKC", text)
    # 统一占位（增强泛化）
    t = _url.sub("<URL>", t)
    t = _email.sub("<EMAIL>", t)
    t = _phone.sub("<PHONE>", t)
    t = _track.sub("<TRACK>", t)
    t = _code.sub("<CODE>", t)
    # 中文块→ASCII
    if USE_PINYIN:
        t = _zhblk.sub(zh_to_pinyin_block, t)
    else:
        t = _zhblk.sub(zh_to_codepoints_block, t)
    # 折叠空白、转小写
    t = re.sub(r"\s+", " ", t).strip().lower()
    return t

STD_LABELS = {"高优先级", "中优先级", "低优先级/垃圾"}
def normalize_label(s: str) -> str:
    s = (s or "").strip().replace(" ", "")
    aliases = {"低优先级": "低优先级/垃圾", "垃圾": "低优先级/垃圾", "低优先级／垃圾":"低优先级/垃圾"}
    s = aliases.get(s, s)
    return s if s in STD_LABELS else "中优先级"

def preprocess_csv(in_csv, out_csv, text_col="text", label_col="label"):
    df = pd.read_csv(in_csv)
    assert text_col in df.columns and label_col in df.columns
    df[text_col]  = df[text_col].astype(str).map(preprocess_for_awe)
    df[label_col] = df[label_col].astype(str).map(normalize_label)
    df = df[(df[text_col].str.len() > 0) & (df[label_col].str.len() > 0)]
    # 确保无额外空格/编码问题
    df.to_csv(out_csv, index=False, quoting=csv.QUOTE_MINIMAL)
    print(f"[预处理] {in_csv} -> {out_csv}  样本数={len(df)}")

# ========== 路径 ==========
train_raw = "train_L1.csv"
test_raw  = "test_L1.csv"
train_pp  = "_train_awe_ascii.csv"
test_pp   = "_test_awe_ascii.csv"
export_dir = "awe_export_ascii"

# 1) 预处理到 ASCII
preprocess_csv(train_raw, train_pp, "text", "label")
preprocess_csv(test_raw,  test_pp,  "text", "label")

# 2) 读入并分层切分
from sklearn.model_selection import train_test_split
df_tr = pd.read_csv(train_pp)
df_te = pd.read_csv(test_pp)
df_te = df_te[df_te["label"].isin(set(df_tr["label"]))].reset_index(drop=True)

df_tr_train, df_tr_val = train_test_split(
    df_tr, test_size=0.2, stratify=df_tr["label"], random_state=42
)
train_clean = "_train_clean.csv"; df_tr_train.to_csv(train_clean, index=False)
val_clean   = "_val_clean.csv";   df_tr_val.to_csv(val_clean, index=False)
test_clean  = "_test_clean.csv";  df_te.to_csv(test_clean, index=False)

# 3) 用 AWE 训练（仅导入 text 子模块，避开顶层依赖）
from mediapipe_model_maker.python.text.text_classifier import dataset as tc_ds
from mediapipe_model_maker.python.text.text_classifier import text_classifier as tc
from mediapipe_model_maker.python.text.text_classifier import text_classifier_options as tc_opt
from mediapipe_model_maker.python.text.text_classifier import model_spec as tc_spec

csv_params = tc_ds.CSVParameters(text_column="text", label_column="label", delimiter=",")
train_data = tc_ds.Dataset.from_csv(train_clean, csv_params=csv_params)
val_data   = tc_ds.Dataset.from_csv(val_clean,   csv_params=csv_params)
test_data  = tc_ds.Dataset.from_csv(test_clean,  csv_params=csv_params)

print(f"\n[数据] 训练={len(train_data)}  验证={len(val_data)}  测试={len(test_data)}")
print("[label_names] →", train_data.label_names)

# —— AWE 超参（包含 shuffle/steps_per_epoch，避免老版本报错）——
BATCH = 32
EPOCHS = 60
LR = 2.5e-3
steps_per_epoch = max(1, len(train_data)//BATCH)

hparams = SimpleNamespace(
    epochs=EPOCHS, batch_size=BATCH, learning_rate=LR,
    steps_per_epoch=steps_per_epoch, class_weights=None,
    shuffle=True, export_dir=export_dir
)

options = tc_opt.TextClassifierOptions(
    supported_model=tc_spec.SupportedModels.AVERAGE_WORD_EMBEDDING_CLASSIFIER,
    hparams=hparams
)

print(f"\n开始训练 AWE（ASCII 预处理）…  epochs={EPOCHS} batch={BATCH} lr={LR}")
model = tc.TextClassifier.create(
    train_data=train_data,
    validation_data=val_data,
    options=options
)
print("训练完成。")

# 4) 评估（Keras）
metrics = model.evaluate(test_data)
try:
    loss, acc = float(metrics[0]), float(metrics[1])
    print(f"\n[Test/Keras] Loss={loss:.4f}  Acc={acc:.4f}")
except Exception:
    print("[Test/Keras] 原始输出：", metrics)

# 5) 导出
try: model.export_model(export_dir=export_dir)
except TypeError: model.export_model()
try: model.export_labels(export_dir=export_dir)
except TypeError: model.export_labels()
print(f"\n模型与标签已导出到：{export_dir}")

# 6) 用“线上同链路（MediaPipe Tasks）”再评估一次，确保一致
from mediapipe.tasks.python import BaseOptions
from mediapipe.tasks.python.text import text_classifier as mp_tc

clf = mp_tc.TextClassifier.create_from_options(
    mp_tc.TextClassifierOptions(
        base_options=BaseOptions(model_asset_path=os.path.join(export_dir, "model.tflite")),
        max_results=3
    )
)

# 注意：CSV 已经是预处理后的文本，直接送入即可
df_eval = pd.read_csv(test_clean)
X = df_eval["text"].astype(str).tolist()
y = df_eval["label"].astype(str).tolist()

correct = 0
for t, gt in zip(X, y):
    r = clf.classify(t)
    cats = r.classifications[0].categories if r.classifications else []
    pred = cats[0].category_name if cats else ""
    if pred == gt: correct += 1
acc_tasks = correct / max(1, len(y))
print(f"[Test/Tasks] Acc={acc_tasks:.4f}  (同线上链路)")
