drop table if exists `files`;
CREATE TABLE files
(
    id            BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '用户ID，主键，自增长，从10000001开始',
    user_id       BIGINT       NOT NULL COMMENT '上传用户ID',
    file_name     VARCHAR(255) NOT NULL COMMENT '图片文件名',
    file_size     BIGINT       NOT NULL COMMENT '图片大小（字节）',
    oss_url       VARCHAR(500) NOT NULL COMMENT 'OSS访问地址',
    INDEX idx_user_id (user_id)
) COMMENT '图片表' CHARSET = utf8mb4  AUTO_INCREMENT = 10000001;

drop table if exists `records`;
CREATE TABLE `records` (
   `id` BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '记录ID',
   `user_id` BIGINT NOT NULL COMMENT '用户ID',
   `action` VARCHAR(20) NOT NULL COMMENT '动作类型：edit/merge',
   `input_oss_url1` VARCHAR(500) NOT NULL COMMENT '输入图片1 OSS地址',
   `input_oss_url2` VARCHAR(500) COMMENT '输入图片2 OSS地址（merge用）',
   `instruction` VARCHAR(1000) COMMENT '用户提示词',
   `result_url` VARCHAR(1000) COMMENT 'Python返回的临时图片URL',
   `output_oss_url` VARCHAR(500) COMMENT '输出图片 OSS地址',
    INDEX `idx_user_id` (`user_id`)
) COMMENT '请求记录表' ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 AUTO_INCREMENT = 10000001;