"""检索离线测试：真实 Flask/Pillow/TF-IDF，模拟 Redis 和 CLIP 编码。"""
import importlib
import json
import math
import os
import tempfile
import unittest
from io import BytesIO
from pathlib import Path
from unittest.mock import MagicMock, patch

import redis
from PIL import Image
from werkzeug.datastructures import MultiDict


def png():
    output = BytesIO()
    Image.new("RGB", (16, 16), "white").save(output, "PNG")
    return output.getvalue()


def vector(score=1.0):
    return [score, math.sqrt(max(0, 1 - score * score)), *([0.0] * 510)]


class SearchImageTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        with patch.dict(os.environ, {"DASHSCOPE_API_KEY": "offline-test-key"}), \
             patch("flask.cli.load_dotenv"), \
             patch("langchain_community.chat_models.ChatTongyi"), \
             patch("deepagents.create_deep_agent"):
            cls.s = importlib.import_module("server")

    def setUp(self):
        self.client = self.s.app.test_client()
        self.cache = {}
        self.ids = set()
        self.redis = MagicMock()
        self.redis.smembers.side_effect = lambda key: self.ids if key == "wear:user:123:images" else set()
        self.redis.mget.side_effect = lambda keys: [self.cache.get(key) for key in keys]
        self.start_patch("server.get_redis", return_value=self.redis)
        self.encode = self.start_patch("server.encode_image_vector", return_value=vector())
        self.cloud = self.start_patch("server.MultiModalConversation.call", side_effect=AssertionError("禁止云调用"))
        self.describe = self.start_patch("server.ChatTongyi", side_effect=AssertionError("禁止云调用"))
        self.root = tempfile.TemporaryDirectory(prefix="search_test_")
        self.addCleanup(self.root.cleanup)
        original = tempfile.TemporaryDirectory
        self.tempdirs = []

        def temporary_directory(**kwargs):
            directory = original(dir=self.root.name, **kwargs)
            self.tempdirs.append(directory.name)
            return directory

        self.start_patch("tempfile.TemporaryDirectory", side_effect=temporary_directory)

    def start_patch(self, target, *args, **kwargs):
        patcher = patch(target, *args, **kwargs)
        result = patcher.start()
        self.addCleanup(patcher.stop)
        return result

    def add_record(self, image_id, **overrides):
        record = {"id": image_id, "userId": "123", "ossUrl": f"https://oss.test/{image_id}.png",
                  "description": "红色上衣", "vector": vector()}
        record.update(overrides)
        self.ids.add(image_id)
        self.cache[f"wear:image:{image_id}"] = json.dumps(record)
        return record

    def request(self, fields):
        response = self.client.post("/api/search-image", data=MultiDict(fields),
                                    content_type="multipart/form-data")
        self.assertTrue(all(not Path(path).exists() for path in self.tempdirs))
        self.assertEqual(list(Path(self.root.name).iterdir()), [])
        self.cloud.assert_not_called()
        self.describe.assert_not_called()
        return response

    def text(self, query="红色上衣", user_id="123"):
        return self.request([("userId", user_id), ("query", query)])

    def image(self, data=None):
        return self.request([("userId", "123"), ("file", (BytesIO(png() if data is None else data), "test.png"))])

    def assert_error(self, response, status):
        self.assertEqual(response.status_code, status, response.json)
        self.assertEqual(response.json["code"], status)
        self.assertEqual(response.json["data"], [])
        self.assertEqual(set(response.json), {"code", "message", "data"})

    def test_chinese_text_similarity_and_order(self):
        self.add_record("exact")
        self.add_record("near", description="红色上衣搭配帽子")
        self.add_record("unrelated", description="蓝鞋")
        response = self.text()
        self.assertEqual(response.status_code, 200)
        self.assertEqual([row["filePath"] for row in response.json["data"]],
                         ["https://oss.test/exact.png", "https://oss.test/near.png"])
        self.assertEqual(response.json["data"][0]["similarity"], 1.0)
        self.assertGreater(response.json["data"][0]["similarity"], response.json["data"][1]["similarity"])
        self.assertEqual(set(response.json["data"][0]), {"filePath", "similarity"})
        self.encode.assert_not_called()
        self.redis.smembers.assert_called_once_with("wear:user:123:images")

    def test_image_cosine_threshold_sorting_and_rounding(self):
        for name, score in (("low", 0.6), ("near", 0.8), ("high", 1.0), ("border", 0.7), ("above", 0.70001)):
            self.add_record(name, vector=vector(score))
        response = self.image()
        self.assertEqual(response.status_code, 200)
        self.assertEqual([row["similarity"] for row in response.json["data"]], [1.0, 0.8, 0.7])
        self.assertEqual(response.json["data"][-1]["filePath"], "https://oss.test/above.png")
        self.encode.assert_called_once_with(png())

    def test_text_threshold_before_rounding(self):
        scores = [("https://oss.test/low", 0.09), ("https://oss.test/border", 0.1),
                  ("https://oss.test/above", 0.10001), ("https://oss.test/high", 0.10004)]
        with patch.object(self.s, "search_text_scores", return_value=scores):
            response = self.text()
        self.assertEqual([row["filePath"] for row in response.json["data"]],
                         ["https://oss.test/high", "https://oss.test/above"])
        self.assertEqual([row["similarity"] for row in response.json["data"]], [0.1, 0.1])

    def test_user_isolation_including_misassigned_record(self):
        self.add_record("own")
        self.add_record("foreign", userId="456")
        for response in (self.text(), self.image()):
            self.assertEqual([row["filePath"] for row in response.json["data"]], ["https://oss.test/own.png"])
        self.assertEqual(self.text(user_id="456").json["data"], [])

    def test_missing_and_broken_records_skipped(self):
        self.add_record("good")
        for image_id, value in (("missing", None), ("broken", "{bad"), ("list", "[]"), ("null", "null")):
            self.ids.add(image_id)
            self.cache[f"wear:image:{image_id}"] = value
        self.add_record("url", ossUrl=None)
        self.add_record("description", description=None)
        response = self.text()
        self.assertEqual(len(response.json["data"]), 1)

    def test_bad_vectors_skipped(self):
        self.add_record("good")
        invalid = [None, [], [0.0] * 512, [1.0] * 511, ["1"] * 512,
                   [True] * 512, [float("nan")] * 512, [float("inf")] * 512, {"x": 1}]
        for index, value in enumerate(invalid):
            self.add_record(str(index), vector=value)
        response = self.image()
        self.assertEqual(response.status_code, 200)
        self.assertEqual(len(response.json["data"]), 1)

    def test_vector_normalization_and_zero_similarity(self):
        self.add_record("scaled", vector=[10.0] + [0.0] * 511)
        self.add_record("orthogonal", vector=[0.0, 1.0] + [0.0] * 510)
        response = self.image()
        self.assertEqual(response.json["data"], [{"filePath": "https://oss.test/scaled.png", "similarity": 1.0}])
        self.assertIsNotNone(self.s.normalize_search_vector([1e308] * 512))

    def test_empty_results(self):
        self.assertEqual(self.text().json, {"code": 200, "message": "查询成功", "data": []})
        self.assertEqual(self.image().json["data"], [])
        self.add_record("empty", description="   ")
        self.assertEqual(self.text().json["data"], [])
        self.assertEqual(self.s.search_text_scores("", [{"ossUrl": "x", "description": ""}]), [])

    def test_parameter_validation(self):
        cases = [[], [("userId", "123")], [("query", "上衣")],
                 [("userId", "123"), ("query", "  ")],
                 [("userId", "123"), ("query", "x"), ("query", "y")],
                 [("userId", "123"), ("userId", "123"), ("query", "x")],
                 [("userId", "123"), ("file", "text")],
                 [("userId", "123"), ("query", "x"), ("extra", "x")]]
        for fields in cases:
            with self.subTest(fields=fields):
                self.assert_error(self.request(fields), 400)
        for user_id in ("0", "-1", "+1", "01", "1.0", "abc", "１２３", " 123", ""):
            with self.subTest(user_id=user_id):
                self.assert_error(self.text(user_id=user_id), 400)
        self.assert_error(self.text(query="x" * 6001), 400)
        self.encode.assert_not_called()
        self.redis.smembers.assert_not_called()

    def test_file_conflicts_and_duplicates(self):
        fields = [("userId", "123"), ("file", (BytesIO(png()), "a.png")), ("query", "x")]
        self.assert_error(self.request(fields), 400)
        fields = [("userId", "123"), ("file", (BytesIO(png()), "a.png")),
                  ("file", (BytesIO(png()), "b.png"))]
        self.assert_error(self.request(fields), 400)
        self.assert_error(self.request([("userId", (BytesIO(png()), "a.png")), ("query", "x")]), 400)

    def test_requires_multipart(self):
        response = self.client.post("/api/search-image", json={"userId": "123", "query": "x"})
        self.assert_error(response, 400)

    def test_image_validation_and_cleanup(self):
        for data in (b"", b"not-image", png()[:20]):
            self.assert_error(self.image(data), 400)
        with patch.object(self.s, "MAX_IMAGE_BYTES", 5):
            self.assert_error(self.image(), 400)
        self.assert_error(self.request([("userId", "123"), ("file", (BytesIO(png()), ""))]), 400)
        self.encode.assert_not_called()

    def test_request_size_limit(self):
        with patch.object(self.s, "MAX_SEARCH_REQUEST_BYTES", 10):
            self.assert_error(self.image(), 400)
        self.encode.assert_not_called()

    def test_redis_failure_is_not_empty_success(self):
        self.redis.smembers.side_effect = redis.ConnectionError("SECRET endpoint")
        response = self.text()
        self.assert_error(response, 500)
        self.assertNotIn("SECRET", response.get_data(as_text=True))

    def test_redis_detail_failure_and_cleanup(self):
        self.add_record("a")
        self.redis.mget.side_effect = redis.TimeoutError("SECRET endpoint")
        self.assert_error(self.image(), 500)

    def test_model_failure_and_cleanup(self):
        self.encode.side_effect = RuntimeError("SECRET path")
        response = self.image()
        self.assert_error(response, 500)
        self.assertNotIn("SECRET", response.get_data(as_text=True))

    def test_invalid_model_vector_is_service_error(self):
        for value in ([], [0.0] * 512, [float("nan")] * 512):
            self.encode.return_value = value
            self.assert_error(self.image(), 500)

    def test_redis_reads_in_batches(self):
        for index in range(205):
            self.add_record(str(index))
        self.assertEqual(len(self.text().json["data"]), 205)
        self.assertEqual(self.redis.mget.call_count, 2)
        self.assertTrue(all(len(call.args[0]) <= 200 for call in self.redis.mget.call_args_list))


if __name__ == "__main__":
    unittest.main()
