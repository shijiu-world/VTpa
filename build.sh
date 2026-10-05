#!/usr/bin/env bash
# 一键构建：代理端 VTpa-<版本>.jar + 子服端 VTpaBridge-<版本>.jar
# ⚠️ 版本号只写在 pom.xml（三个：父 + proxy + bridge）和 VTpa.java 的 @Plugin(version=...)
# 依赖已经全部在 ~/.m2 里了，所以走离线（-o），断网也能编。
set -e
cd "$(dirname "$0")"

export JAVA_HOME="D:/Code/Java/zulu25.34.17-ca-jdk25.0.3-win_x64"
MVN="D:/Code/Java/apache-maven-3.9.10/bin/mvn"

# 这两个类在两个 jar 里各存一份（互相不依赖），改了只改 proxy 那份，
# 这里自动复制过去 —— 免得改了协议/粒子语法却忘了同步，两边解析错位。
for f in Wire FxSpec; do
  sed 's/^package cn\.shijiu\.vtpa;/package cn.shijiu.vtpa.bridge;/' \
      "proxy/src/main/java/cn/shijiu/vtpa/$f.java" \
      > "bridge/src/main/java/cn/shijiu/vtpa/bridge/$f.java"
done
echo "已同步 Wire / FxSpec 到 bridge 模块"

"$MVN" -B -o package

echo
echo "产物："
ls -1 proxy/target/VTpa-*.jar bridge/target/VTpaBridge-*.jar
