# 图片编辑与多图换装

`POST /api/skill/image`，使用 `multipart/form-data`。成功返回 HTTP 200：

```json
{"success": true, "url": "https://example.com/result.png"}
```

参数、文件及业务校验失败返回 HTTP 400；Agent、分类服务或生成模型异常返回 HTTP 500：

```json
{"success": false, "error": "相同服饰类型只能上传一张照片"}
```

## 请求示例

Windows PowerShell 使用 `curl.exe`，由 `-F` 自动生成带 boundary 的 Content-Type，不要手工填写 boundary。

单图编辑：原来的 `file`、`instruction` 和 `qwen-image-3.0-pro` 模型保留。

```powershell
curl.exe http://localhost:5000/api/skill/image -F "file=@D:/images/photo.png" -F "instruction=将图片背景改为浅蓝色"
```

人物 + 上衣 + 裤子 + 帽子：

```powershell
curl.exe http://localhost:5000/api/skill/image -F "subject=@D:/images/person.png" -F "items=@D:/images/top.png" -F "items=@D:/images/pants.png" -F "items=@D:/images/hat.png" -F "instruction=穿上所有参考服饰，保留原本姿势和面部特征"
```

宠物 + 帽子 + 围巾（演示 `items[]`）：

```powershell
curl.exe http://localhost:5000/api/skill/image -F "subject=@D:/images/cat.png" -F "items[]=@D:/images/hat.png" -F "items[]=@D:/images/scarf.png" -F "instruction=给猫戴上帽子和围巾，保持猫的身份、毛色和姿态"
```

Postman：选择 POST，Body → form-data。`instruction` 设为 Text；其他字段设为 File。
添加多个同名 `items` 行即可上传多个服饰，也兼容 `items[]`；两种名称同时出现时会合并校验。

## 校验与处理

- `file` 与 `subject/items` 不可混用；`file` 和 `subject` 均只允许一张。
- `instruction` 必填且不能全为空白，最多6000字符；不接受重复 instruction。
- 每张图片最大10 MiB，支持 JPEG、PNG、WEBP、BMP、GIF；验证实际内容，不信任扩展名。整个请求限制81 MiB。
- `items` 必须有1～7张。类别为上衣、裤子、裙子、帽子、鞋子、围巾、配饰，每类最多一张。
- 外套、毛衣、衬衫等统一为上衣；连衣裙和半身裙归裙子；一双鞋按一件鞋子处理。
- 包、腰带、首饰、眼镜等统一归配饰，因此两个不同配饰也会被视为重复。
- 含多件独立服饰或无法确定单一类别的图片会被拒绝；请按单件服饰上传。
- 主体必须被识别为人物或小动物/宠物。非服饰、未知类别、重复类别都在生成前拒绝。
- 上传先保存到请求独立的临时目录。成功、失败、部分文件校验失败及 Agent 异常都会通过上下文管理器清理；进程被强制终止等非正常退出不在此保证范围内。

Agent 根据 `image_path` 或 `subject_path + item_paths` 选择工具。服务端只授权该请求的原始参数，
工具必须完整接收本次上传路径，不能遗漏服饰、替换路径或选择另一模式。
工具输出仍是 JSON 字符串。HTTP 响应使用工具实际执行结果，避免最终模型文本编造 URL；
同一请求重复调用工具复用结果，不重复生成。不同 HTTP 请求之间不做幂等去重。

分类复用“视觉模型描述 + qwen-plus 严格分类”思路。`describe_image()` 新增可选描述提示词，
原审核接口仍使用原默认提示词，原 `validate_image()` 及审核响应契约不变。
分类错误格式或调用异常按服务错误处理，模型明确输出 unknown 按业务校验失败处理。

## 多图模型适配

原单图工具继续使用 `qwen-image-3.0-pro`。该模型最多3张参考图，无法直接处理主体加3件及以上服饰。
新增换装工具使用 `qwen-image-2.1-pro`，支持最多10张参考图。本接口最多8张图片：1张主体 + 7类服饰。
不做服饰拼贴或逐件生成：同一个 `MultiModalConversation.call()` 中，第一张为主体，之后依次为全部服饰，
最后是一条包含图号、服饰类别、身份/姿势保持要求和用户偏好的文本指令，输出一张图。

参考：[官方多图编辑说明](https://help.aliyun.com/zh/model-studio/qwen-image-edit-guide)。
模型可用性受账号、地域和权限影响；没有自动降级到输入数量不足的模型。
如部署环境需换模型，修改 `OUTFIT_MODEL` 和 `generate_image()` 适配，并核对该模型输入数量及协议。
继续使用原有 `DASHSCOPE_API_KEY`，无需新增环境变量；不向客户端回显供应商原始异常。

## 测试与实际验收

在项目根目录运行：

```powershell
.\.venv\Scripts\python.exe -B -m unittest discover -s tests -v
```

离线测试模拟全部云端调用，使用真实 Flask、Pillow 和 LangChain tool，并另测真实 DeepAgents 图执行。
测试使用虚拟密钥，禁止将模拟结果当作云端识别或生成效果的证据。

| 实际上传场景 | 预期 |
| --- | --- |
| 单图 file + instruction | 200，success=true，URL 可打开，单图编辑符合指令 |
| 人物 + 上衣 + 裤子 + 帽子 | 200，全部服饰出现在同一主体上 |
| 动物 + 帽子 + 围巾 | 200，主体物种、毛色和姿态尽量保持 |
| 人物 + 两张上衣 | 400，相同服饰类型只能上传一张照片，不调用生成模型 |
| 建筑/风景作 subject + 合法服饰 | 400，subject 必须是人物或小动物图片 |
| 合法 subject + 帽子 + 水果图片 | 400，items 包含非服饰或无法识别的图片，不调用生成模型 |

真实验收需配置有效密钥并确认账号可调用原视觉模型 `qwen3.8-max`、分类/Agent 模型 `qwen-plus`、
单图模型 `qwen-image-3.0-pro` 和新增多图模型 `qwen-image-2.1-pro`，然后运行上述请求并打开结果 URL 检查。
分类准确率、主体一致性、服饰适配质量及云端耗时仍须真实图片测试，提示词不能保证生成质量。

本次离线测试：19个测试方法全部通过（含多组子场景）。实际安装版本：Flask 3.1.3、Pillow 12.1.0、
DashScope 1.27.7、DeepAgents 0.7.28、langchain-core 1.6.12、langchain-community 0.4.1。
其中部分版本高于 requirements.txt 的锁定版本；本次没有修改依赖，也没有验证锁定版本下的运行结果。
未执行真实 DashScope 请求。
