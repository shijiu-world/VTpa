#!/usr/bin/env bash
# 跑代理端的冒烟测试（纯逻辑，不开服）：TOML 解析 / 配置取值 / 请求账本 / 插件消息协议
# 用法：./run-tests.sh
set -e
cd "$(dirname "$0")/proxy"

JDK="D:/Code/Java/zulu25.34.17-ca-jdk25.0.3-win_x64"
M2="C:/Users/PC/.m2/repository"

CP="$M2/com/velocitypowered/velocity-api/3.2.0-SNAPSHOT/velocity-api-3.2.0-SNAPSHOT.jar"
CP="$CP;$M2/net/kyori/adventure-api/4.14.0/adventure-api-4.14.0.jar"
CP="$CP;$M2/net/kyori/adventure-key/4.14.0/adventure-key-4.14.0.jar"
CP="$CP;$M2/net/kyori/adventure-text-serializer-legacy/4.14.0/adventure-text-serializer-legacy-4.14.0.jar"
CP="$CP;$M2/net/kyori/adventure-text-serializer-plain/4.14.0/adventure-text-serializer-plain-4.14.0.jar"
CP="$CP;$M2/net/kyori/adventure-text-serializer-gson/4.14.0/adventure-text-serializer-gson-4.14.0.jar"
CP="$CP;$M2/net/kyori/adventure-text-serializer-json/4.14.0/adventure-text-serializer-json-4.14.0.jar"
CP="$CP;$M2/net/kyori/examination-api/1.3.0/examination-api-1.3.0.jar"
CP="$CP;$M2/net/kyori/examination-string/1.3.0/examination-string-1.3.0.jar"
CP="$CP;$M2/net/kyori/option/1.1.0/option-1.1.0.jar"
CP="$CP;$M2/org/slf4j/slf4j-api/1.7.36/slf4j-api-1.7.36.jar"
CP="$CP;$M2/org/checkerframework/checker-qual/3.33.0/checker-qual-3.33.0.jar"

# Git Bash 的 pwd 给的是 /d/... 这种路径，javac/java 是 Windows 程序，认不了，要用 -W 转成 D:/...
BASE="$(pwd -W)"
rm -rf target/testout
mkdir -p target/testout

"$JDK/bin/javac" -encoding UTF-8 -cp "$BASE/target/classes;$CP" -d target/testout tests/SmokeTest.java
"$JDK/bin/java" -Dfile.encoding=UTF-8 -cp "$BASE/target/classes;$BASE/target/testout;$CP" \
    SmokeTest "$BASE/src/main/resources/config.toml"
