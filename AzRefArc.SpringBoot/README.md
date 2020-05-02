# 実行

```
mvnw spring-boot:run

```
→ http://localhost:8080/ でサーバが起動

# パッケージング
```
mvn clean package -DskipTests
```
jar ファイル作成

# Docker イメージ構築
## ビルド方法(マルチステージビルド) ※ Dockerfile のあるパス上で実行する

```
docker build -t azrefarc.springboot:1 -f ./Dockerfile .
```

# ファイルを差し替えてリビルド
```
cp index_v2.html src/main/resources/templates/index.html

docker build -t azrefarc.springboot:2 -f ./Dockerfile .
```

