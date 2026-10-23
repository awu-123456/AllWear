"""离线测试上传图片索引接口：模拟 OSS 下载、视觉模型、CLIP 和 Redis。"""
import importlib
import json
import os
import unittest
from io import BytesIO
from types import SimpleNamespace
from unittest.mock import MagicMock, patch

from PIL import Image


def png():
    output = BytesIO()
    Image.new("RGB", (32, 32), "white").save(output, "PNG")
    return output.getvalue()


class FakeRedis:
    def __init__(self):
        self.store = {}
        self.sets = {}

    def pipeline(self):
        return FakePipeline(self)


class FakePipeline:
    def __init__(self, client):
        self.client = client
        self.ops = []

    def set(self, key, value):
        self.ops.append(("set", key, value))
        return self

    def sadd(self, key, value):
        self.ops.append(("sadd", key, value))
        return self

    def execute(self):
        for op in self.ops:
            if op[0] == "set":
                self.client.store[op[1]] = op[2]
            else:
                self.client.sets.setdefault(op[1], set()).add(op[2])
        return [True] * len(self.ops)


class UploadImageTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        with patch.dict(os.environ, {"DASHSCOPE_API_KEY": "offline-test-key"}), \
             patch("flask.cli.load_dotenv"), \
             patch("langchain_community.chat_models.ChatTongyi"), \
             patch("deepagents.create_deep_agent"):
            cls.server = importlib.import_module("server")

    def setUp(self):
        self.s = self.server
        self.client = self.s.app.test_client()
        self.redis = FakeRedis()
        self.start_patch("server.API_KEY", "offline-test-key")
        self.start_patch("server.get_redis", return_value=self.redis)
        self.start_patch("server.download_image_from_url", return_value=png())
        self.describe_patcher = patch(
            "server.describe_image_with_qwen_vl", return_value="白色短袖T恤，简约款",
        )
        self.describe = self.describe_patcher.start()
        self.addCleanup(self.describe_patcher.stop)
        self.encode_patcher = patch(
            "server.encode_image_vector", return_value=[0.1] * self.s.CLIP_VECTOR_DIM,
        )
        self.encode = self.encode_patcher.start()
        self.addCleanup(self.encode_patcher.stop)

    def start_patch(self, target, *args, **kwargs):
        patcher = patch(target, *args, **kwargs)
        value = patcher.start()
        self.addCleanup(patcher.stop)
        return value

    def test_upload_saves_json_in_redis(self):
        response = self.client.post("/api/upload-image", json={
            "userId": "u1001",
            "ossUrl": "https://oss.example.test/a.png",
        })
        self.assertEqual(response.status_code, 200, response.json)
        body = response.json
        self.assertEqual(body["code"], 200)
        self.assertEqual(body["userId"], "u1001")
        self.assertEqual(body["description"], "白色短袖T恤，简约款")
        self.assertEqual(body["vectorDim"], 512)
        self.assertNotIn("vector", body)

        image_id = body["id"]
        record = json.loads(self.redis.store[f"wear:image:{image_id}"])
        self.assertEqual(record["userId"], "u1001")
        self.assertEqual(record["ossUrl"], "https://oss.example.test/a.png")
        self.assertEqual(record["description"], "白色短袖T恤，简约款")
        self.assertEqual(len(record["vector"]), 512)
        self.assertIn(image_id, self.redis.sets["wear:images"])
        self.assertIn(image_id, self.redis.sets["wear:user:u1001:images"])
        self.describe.assert_called_once_with("https://oss.example.test/a.png")
        self.encode.assert_called_once()

    def test_missing_fields(self):
        response = self.client.post("/api/upload-image", json={"userId": "u1"})
        self.assertEqual(response.status_code, 400)
        self.assertIn("ossUrl", response.json["error"])
        response = self.client.post("/api/upload-image", json={
            "ossUrl": "https://oss.example.test/a.png",
        })
        self.assertEqual(response.status_code, 400)
        self.assertIn("userId", response.json["error"])

    def test_non_object_json_rejected_before_models(self):
        for payload in (["x"], [], "x", 1, True):
            with self.subTest(payload=payload):
                response = self.client.post("/api/upload-image", json=payload)
                self.assertEqual(response.status_code, 400)
        self.describe.assert_not_called()
        self.encode.assert_not_called()

    def test_validate_missing_file_rejected_before_models(self):
        with patch.object(self.s, "describe_image") as describe:
            response = self.client.post("/api/validate-image", data={})
        self.assertEqual(response.status_code, 400)
        self.assertFalse(response.json["allow"])
        describe.assert_not_called()

    def test_clip_vector_with_real_transformers_output(self):
        import torch
        from transformers import CLIPConfig, CLIPModel, CLIPTextConfig, CLIPVisionConfig

        vision = CLIPVisionConfig(hidden_size=32, intermediate_size=64,
                                  num_hidden_layers=1, num_attention_heads=4,
                                  image_size=32, patch_size=16)
        text = CLIPTextConfig(hidden_size=32, intermediate_size=64,
                              num_hidden_layers=1, num_attention_heads=4)
        model = CLIPModel(CLIPConfig(text_config=text.to_dict(),
                                    vision_config=vision.to_dict(), projection_dim=512))
        model.eval()
        processor = MagicMock(return_value={"pixel_values": torch.zeros(1, 3, 32, 32)})
        self.encode_patcher.stop()
        with patch.object(self.s, "get_clip", return_value=(model, processor, "cpu")):
            vector = self.s.encode_image_vector(png())
        self.assertEqual(len(vector), 512)
        self.assertTrue(all(isinstance(value, float) for value in vector))
        self.assertAlmostEqual(sum(value * value for value in vector), 1.0, places=5)

    def test_invalid_url_rejected_before_models(self):
        self.start_patch("server.download_image_from_url",
                         side_effect=self.s.ImageInputError("ossUrl 必须是有效的 http/https 地址"))
        response = self.client.post("/api/upload-image", json={
            "userId": "u1", "ossUrl": "ftp://x",
        })
        self.assertEqual(response.status_code, 400)
        self.describe.assert_not_called()

    def test_qwen_vl_uses_langchain_chat_tongyi(self):
        llm = MagicMock()
        llm.invoke.return_value = SimpleNamespace(content="蓝色牛仔裤")
        with patch.object(self.s, "download_image_from_url", return_value=png()), \
             patch.object(self.s, "encode_image_vector", return_value=[0.0] * 512), \
             patch.object(self.s, "ChatTongyi", return_value=llm) as chat:
            self.describe_patcher.stop()
            response = self.client.post("/api/upload-image", json={
                "userId": "u2",
                "ossUrl": "https://oss.example.test/jeans.png",
            })
        self.assertEqual(response.status_code, 200, response.json)
        chat.assert_called_once()
        self.assertEqual(chat.call_args.kwargs["model_name"], "qwen3-vl-plus")
        message = llm.invoke.call_args.args[0][0]
        self.assertEqual(message.content[0]["image"], "https://oss.example.test/jeans.png")
        self.assertEqual(response.json["description"], "蓝色牛仔裤")


if __name__ == "__main__":
    unittest.main()
