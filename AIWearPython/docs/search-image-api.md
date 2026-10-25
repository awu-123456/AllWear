# 图片检索接口

`POST http://localhost:5000/api/search-image`

Postman：Body → form-data，不手动填写 Content-Type，交由 Postman 自动生成 boundary。

## 文搜图

| Key | 类型 | Value 示例 |
| --- | --- | --- |
| userId | Text | 10000003 |
| query | Text | 深绿色高领上衣 |

不要添加 file 行。query 必须非空，最多 6000 字符。userId 使用无前导零的正整数字符串。

## 图搜图

| Key | 类型 | Value 示例 |
| --- | --- | --- |
| userId | Text | 10000003 |
| file | File | 选择本地图片 |

不要添加 query 行。图片最大 10 MiB，总请求最大 11 MiB，支持 JPEG、PNG、WEBP、BMP、GIF，校验实际内容。

## 返回格式

```json
{"code":200,"message":"查询成功","data":[{"filePath":"https://example.com/image.png","similarity":0.8162}]}
```

没有结果时 data 为 []。参数错误返回 HTTP 400；Redis、模型等服务错误返回 HTTP 500，data 均为 []。
filePath 来自索引中的 ossUrl。原始分数严格大于阈值才返回，按原始分数降序排列，最后保留四位小数；因此显示为 0.7000 的结果也可能符合大于 0.7 的条件。

## 实现说明

读取现有 wear:user:{userId}:images Set，再分批 MGET wear:image:{id} JSON。只读取用户集合中的记录并核对记录 userId；不扫描其他用户或 jwt:*。损坏、缺失、字段异常的记录跳过，Redis 故障返回 500。

文搜图新增 scikit-learn==1.8.0 依赖（安装时自动引入其依赖），使用 TfidfVectorizer(analyzer="char", ngram_range=(1, 2), norm="l2")，查询和本用户描述共同拟合词表和 IDF，再计算余弦相似度，阈值 > 0.1。这是字符重合检索，不能保证同义词或跨语言的语义匹配；阈值和效果需真实数据验证。

图搜图复用本地 CLIP 512 维编码，对查询及缓存向量检查有限数值、维度与非零范数，再归一化计算余弦相似度，阈值 > 0.7。临时目录在成功和失败时自动清理。

接口中的 userId 仅用于选择用户数据，生产对接时由 Java 从认证身份中取得，并限制 Python 服务访问范围。

安装新增依赖：

```powershell
.\.venv\Scripts\python.exe -m pip install scikit-learn==1.8.0
```

运行全部离线测试：

```powershell
.\.venv\Scripts\python.exe -B -m unittest discover -s tests -v
```

检索测试使用真实 TF-IDF、Flask 和 Pillow，Redis 与 CLIP 编码被模拟，不调用付费模型。模拟通过不能证明真实图片的检索效果。
