# 1、初始化数据库：创建业务数据库bite_wear
# 2、创建用户，用户名：bite 密码：bite@123
# 3、授予bite用户特定权限

CREATE database if NOT EXISTS `bite_wear` default character set utf8mb4 collate utf8mb4_general_ci;

CREATE USER 'bite'@'%' IDENTIFIED BY 'bite@123';
grant replication slave, replication client on *.* to 'bite'@'%';

GRANT ALL PRIVILEGES ON bite_wear.* TO  'bite'@'%';

FLUSH PRIVILEGES;
