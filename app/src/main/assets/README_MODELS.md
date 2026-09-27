# On-device models

SmartGuard runs 100% offline (the manifest strips the INTERNET permission). All models run locally.

## Bundled in the APK (this `assets/` folder)

| File | Used by | Status |
|---|---|---|
| `MobileFaceNet.tflite` (5.2 MB) | `FaceEmbedder` — face embedding | **Bundled.** Input `1x112x112x3` (`(px-127.5)/128`), output `1x192`, L2-normalized. Match threshold cosine ≥ 0.60 (= squared-L2 < 0.8, the model authors' operating point). Source: github.com/syaringan357/Android-MobileFaceNet-MTCNN-FaceAntiSpoofing (git blob `3249c511…`). |
| `antispoof_minifasnetv2se.tflite` | `LivenessManager` — passive anti-spoof on every recognition frame | **Bundled.** MiniFASNetV2-SE from github.com/facenox/face-antispoof-onnx (`models/best/98.20/best_model.onnx`, SHA-256 `af2381b8…c55a42b6`, Apache-2.0; 98.2% accuracy, ROC-AUC 0.998 on ~70k CelebA-Spoof images), converted with onnx2tf and checked against the ONNX output. Input RGB `[0,1]` 128×128 square crop (kept inside the frame, never black-padded); output logits `[real, spoof]`, P(real) = sigmoid(real − spoof). **Tuned on this phone** (128 real frames incl. low/tilted/backlit/dim/blurry + photo-on-phone attacks): crop 1.3× the face box, live if P ≥ 0.12 → 84% of real frames live, 97% of real checks pass within 5 frames, every attack frame ≤ 0.001. (Model default 1.5× / 0.5 gave 52% / 79%.) If absent, passive liveness is off. |

Liveness is **passive only**: no prompt, the model checks the same frames used for recognition (a match
needs 2 consecutive live frames; 2 spoof-looking frames reject). An active blink / head-turn challenge
exists (`ActiveLivenessChallenge`, `activeLivenessForParents`) but is OFF — it slowed unlocking.

Shapes are read from the model at load time, so any MobileFaceNet/FaceNet export works (a fixed
batch dimension is handled too). **Re-enroll faces after changing the embedding model** — embeddings
from a different model/dimension are ignored by the matcher.

## On-device LLM (NOT in the APK — pushed to the phone)

The Policy Assistant uses the **Google AI Edge / MediaPipe LLM Inference API**
(`com.google.mediapipe:tasks-genai:0.10.35`) with a `.task` model bundle loaded from local storage.

Installed model: `Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv1280.task` (1.6 GB, Apache-2.0,
SHA-256 `8d867a7c…94b42e0`, from huggingface.co/litert-community/Qwen2.5-1.5B-Instruct).
A copy is kept in `D:\P2\models\`.

Push it to the phone (the app looks in these folders, in order):
```
adb shell mkdir -p /sdcard/Android/data/com.smartguard/files/models
adb push D:\P2\models\Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv1280.task /sdcard/Android/data/com.smartguard/files/models/
```
1. `/sdcard/Android/data/com.smartguard/files/models/*.task`
2. `<app files dir>/models/*.task`
3. `/data/local/tmp/llm/*.task`

Note: uninstalling the app deletes folder 1 — re-push after a reinstall (updates via `adb install -r` keep it).

To use Gemma instead: download `gemma3-1b-it-int4.task` (555 MB) from
huggingface.co/litert-community/Gemma3-1B-IT (requires accepting the Gemma license) and push it to
the same folder. The Gemma chat template is selected automatically from the file name.

Design rule: the LLM only translates text into a `PolicySpec` JSON. `LlmPolicyParser` sanitizes it
and the deterministic `PolicyEngine` validates and enforces it. If the model is missing or its output
isn't a usable policy, the rule-based translator handles the request and the UI says so.

Unknown faces go to **Guest mode** (limited apps, 30 minutes). Age estimation was removed.
