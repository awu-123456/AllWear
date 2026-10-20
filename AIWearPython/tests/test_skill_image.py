"""离线测试：使用真实 Flask/Pillow/tool，模拟所有付费模型调用。"""
import importlib
import json
import os
import tempfile
import unittest
from io import BytesIO
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

from PIL import Image
from langchain_core.messages import AIMessage
from werkzeug.datastructures import MultiDict


def png():
    output = BytesIO()
    Image.new("RGB", (32, 32), "white").save(output, "PNG")
    return output.getvalue()


class ModelResponse(dict):
    status_code = 200


class RoutingAgent:
    """模拟 agent 决策；工具、分类流程和 HTTP 路由仍执行真实代码。"""
    def __init__(self, server):
        self.server = server
        self.calls = []

    def invoke(self, state, config=None):
        arguments = json.loads(state["messages"][0]["content"])
        self.calls.append(arguments)
        selected = (self.server.edit_image_tool if "image_path" in arguments
                    else self.server.merge_outfit_tool)
        output = selected.invoke(arguments)
        return {"messages": [AIMessage(content=output)]}


class SkillImageTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        with patch.dict(os.environ, {"DASHSCOPE_API_KEY": "offline-test-key"}), \
             patch("flask.cli.load_dotenv"), \
             patch("langchain_community.chat_models.ChatTongyi"), \
             patch("deepagents.create_deep_agent"):
            cls.server = importlib.import_module("server")

    def setUp(self):
        self.s = self.server
        self.agent = RoutingAgent(self.s)
        self.root = tempfile.TemporaryDirectory(prefix="aiwear_test_")
        self.addCleanup(self.root.cleanup)
        original_temporary_directory = tempfile.TemporaryDirectory
        self.tempdirs = []

        def tracked_directory(**kwargs):
            directory = original_temporary_directory(dir=self.root.name, **kwargs)
            self.tempdirs.append(directory.name)
            return directory

        self.start_patch("tempfile.TemporaryDirectory", side_effect=tracked_directory)
        self.start_patch("server.API_KEY", "offline-test-key")
        self.start_patch("server.deep_agent", self.agent)
        self.describe = self.start_patch("server.describe_image", return_value="图片描述 {文字}")
        self.classifier = self.start_patch("server.ChatTongyi")
        self.call = self.start_patch("server.MultiModalConversation.call", return_value=ModelResponse({
            "output": {"choices": [{"message": {"content": [
                {"text": "generated"}, {"image": "https://example.test/result.png"},
            ]}}]},
        }))
        self.client = self.s.app.test_client()

    def start_patch(self, target, *args, **kwargs):
        patcher = patch(target, *args, **kwargs)
        value = patcher.start()
        self.addCleanup(patcher.stop)
        return value

    def labels(self, *labels):
        self.classifier.return_value.invoke.side_effect = [SimpleNamespace(content=x) for x in labels]

    def request(self, fields, instruction="请自然穿戴所有服饰"):
        data = MultiDict()
        if instruction is not None:
            data.add("instruction", instruction)
        for field in fields:
            if isinstance(field, tuple):
                name, content = field
            else:
                name, content = field, png()
            data.add(name, (BytesIO(content), "upload.png"))
        response = self.client.post("/api/skill/image", data=data, content_type="multipart/form-data")
        self.assertTrue(all(not Path(path).exists() for path in self.tempdirs))
        self.assertEqual(list(Path(self.root.name).iterdir()), [])
        self.assertIsNone(self.s._image_task.get())
        return response

    def assert_failure(self, response, status, text):
        self.assertEqual(response.status_code, status, response.json)
        self.assertEqual(set(response.json), {"success", "error"})
        self.assertFalse(response.json["success"])
        self.assertIn(text, response.json["error"])

    def test_single_image_preserves_model_and_response(self):
        response = self.request(["file"])
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json, {"success": True, "url": "https://example.test/result.png"})
        self.assertEqual(self.call.call_args.kwargs["model"], "qwen-image-3.0-pro")
        self.describe.assert_not_called()

    def test_person_and_three_items_sent_in_one_call(self):
        self.labels("人物", "上衣", "裤子", "帽子")
        response = self.request(["subject", "items", "items", "items"])
        self.assertEqual(response.status_code, 200, response.json)
        self.call.assert_called_once()
        kwargs = self.call.call_args.kwargs
        self.assertEqual(kwargs["model"], "qwen-image-2.1-pro")
        content = kwargs["messages"][0]["content"]
        self.assertEqual(len([part for part in content if "image" in part]), 4)
        self.assertIn("图4是帽子", content[-1]["text"])

    def test_pet_hat_scarf_and_both_item_aliases(self):
        for fields in (["subject", "items[]", "items[]"], ["subject", "items", "items[]"]):
            with self.subTest(fields=fields):
                self.labels("动物", "帽子", "围巾")
                self.assertEqual(self.request(fields).status_code, 200)

    def test_every_duplicate_category_blocks_generation(self):
        for category in self.s.CLOTHING_TYPES:
            with self.subTest(category=category):
                self.labels("人物", category, category)
                self.assert_failure(self.request(["subject", "items", "items"]), 400,
                                    "相同服饰类型只能上传一张照片")
        self.call.assert_not_called()

    def test_invalid_subject_blocks_generation(self):
        self.labels("unknown")
        self.assert_failure(self.request(["subject", "items"]), 400, "subject 必须是人物或小动物图片")
        self.call.assert_not_called()

    def test_non_clothing_and_uncertain_item_block_generation(self):
        self.labels("人物", "帽子", "unknown")
        self.assert_failure(self.request(["subject", "items", "items"]), 400, "第2张")
        self.call.assert_not_called()

    def test_maximum_seven_distinct_categories(self):
        self.labels("人物", *self.s.CLOTHING_TYPES)
        self.assertEqual(self.request(["subject"] + ["items"] * 7).status_code, 200)
        content = self.call.call_args.kwargs["messages"][0]["content"]
        self.assertEqual(len(content), 9)  # 8张图片 + 文本。

    def test_parameter_errors(self):
        cases = [([], "x"), (["file"], None), (["file"], "  "),
                 (["file", "file"], "x"), (["subject"], "x"), (["items"], "x"),
                 (["subject", "subject", "items"], "x"),
                 (["file", "subject", "items"], "x"), (["file", "extra"], "x"),
                 (["subject"] + ["items"] * 8, "x"), (["file"], "x" * 6001)]
        for fields, instruction in cases:
            with self.subTest(fields=fields, instruction_length=len(instruction or "")):
                self.assert_failure(self.request(fields, instruction), 400, "")
        self.assertEqual(self.agent.calls, [])
        self.call.assert_not_called()

    def test_invalid_images_and_partial_upload_cleanup(self):
        for content in (b"", b"not an image", png()[:30]):
            with self.subTest(content=content):
                self.assert_failure(self.request(["subject", ("items", content)]), 400, "items")
        with patch.object(self.s, "MAX_IMAGE_BYTES", 5):
            self.assert_failure(self.request(["file"]), 400, "10MB")
        self.call.assert_not_called()

    def test_non_multipart_and_text_file_field(self):
        response = self.client.post("/api/skill/image", json={"instruction": "x"})
        self.assert_failure(response, 400, "multipart/form-data")
        response = self.client.post("/api/skill/image", data={"instruction": "x", "file": "path"},
                                    content_type="multipart/form-data")
        self.assert_failure(response, 400, "必须上传文件")

    def test_total_request_limit(self):
        with patch.object(self.s, "MAX_REQUEST_BYTES", 10):
            self.assert_failure(self.request(["file"]), 400, "请求过大")

    def test_service_unavailable_cleans_files(self):
        with patch.object(self.s, "deep_agent", None):
            self.assert_failure(self.request(["file"]), 500, "服务未就绪")
        with patch.object(self.s, "API_KEY", None):
            self.assert_failure(self.request(["file"]), 500, "服务未就绪")

    def test_model_error_status_and_missing_url(self):
        self.call.return_value.status_code = 403
        self.assert_failure(self.request(["file"]), 500, "图像模型调用失败")
        self.call.return_value = ModelResponse({"output": {"choices": []}})
        self.assert_failure(self.request(["file"]), 500, "未返回有效")

    def test_cloud_exception_is_redacted(self):
        self.call.side_effect = RuntimeError("SECRET_KEY user path and request data")
        response = self.request(["file"])
        self.assert_failure(response, 500, "服务异常")
        self.assertNotIn("SECRET_KEY", response.get_data(as_text=True))

    def test_classifier_error_and_invalid_output(self):
        for output in ("是", "上衣和裤子", "", ["人物"]):
            with self.subTest(output=output):
                self.labels(output)
                self.assert_failure(self.request(["subject", "items"]), 500, "分类模型返回格式异常")
        self.describe.side_effect = RuntimeError("SECRET_KEY")
        self.assert_failure(self.request(["subject", "items"]), 500, "服务异常")
        self.call.assert_not_called()

    def test_agent_cannot_invent_success_or_omit_items(self):
        with patch.object(self.agent, "invoke", return_value={"messages": [
            AIMessage(content='{"success":true,"url":"https://invented.test/a.png"}')
        ]}):
            self.assert_failure(self.request(["file"]), 500, "未执行匹配")

        def wrong_arguments(state, config=None):
            arguments = json.loads(state["messages"][0]["content"])
            arguments["item_paths"] = arguments["item_paths"][:1]
            self.s.merge_outfit_tool.invoke(arguments)
        with patch.object(self.agent, "invoke", side_effect=wrong_arguments):
            self.assert_failure(self.request(["subject", "items", "items"]), 500, "未执行匹配")
        self.call.assert_not_called()

    def test_duplicate_tool_call_generates_only_once(self):
        original = self.agent.invoke

        def repeat(state, config=None):
            original(state, config)
            return original(state, config)
        with patch.object(self.agent, "invoke", side_effect=repeat):
            self.assertEqual(self.request(["file"]).status_code, 200)
        self.call.assert_called_once()

    def test_actual_deepagents_graph_executes_tools(self):
        from deepagents import create_deep_agent
        from langchain_core.language_models.fake_chat_models import FakeMessagesListChatModel

        class ToolModel(FakeMessagesListChatModel):
            def bind_tools(self, tools, **kwargs):
                return self

        def run_graph(state, config=None):
            arguments = json.loads(state["messages"][0]["content"])
            name = "edit_image_tool" if "image_path" in arguments else "merge_outfit_tool"
            model = ToolModel(responses=[
                AIMessage(content="", tool_calls=[{"name": name, "args": arguments, "id": "call1"}]),
                AIMessage(content="agent final text is not trusted"),
            ])
            graph = create_deep_agent(model=model, tools=self.s.skills_tools,
                                      skills=["/skills/"], system_prompt=self.s.AGENT_SYSTEM_PROMPT)
            return graph.invoke(state, config=config)

        with patch.object(self.agent, "invoke", side_effect=run_graph):
            self.assertEqual(self.request(["file"]).status_code, 200)
            self.labels("动物", "帽子", "围巾")
            self.assertEqual(self.request(["subject", "items", "items"] ).status_code, 200)

    def test_existing_validate_route_contract(self):
        with patch.object(self.s, "validate_image", return_value=True):
            response = self.client.post("/api/validate-image", data={"file": (BytesIO(png()), "a.png")})
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json, {"code": 200, "allow": True, "description": "图片描述 {文字}"})


if __name__ == "__main__":
    unittest.main()
