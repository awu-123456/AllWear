#模型下载
from modelscope import snapshot_download
model_dir = snapshot_download('openai-mirror/clip-vit-base-patch16')

# print("模型下载位置：", model_dir)