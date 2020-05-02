# ビルド方法(マルチステージビルド)
# ※ Dockerfile のあるパス上で実行する
# docker build -t azrefarc.springboot:1 -f ./Dockerfile .
# 実行方法
# docker run --name azrefarc.springboot:1 -i azrefarc.springboot:1

# ビルドを行うために jdk を利用
FROM mcr.microsoft.com/java/jdk:8u192-zulu-alpine AS build

# ソースコードをコピー
COPY . /source
WORKDIR /source

# 改行コード修正と実行権限の修正
RUN sed -i 's/\r$//' mvnw
RUN chmod 777 mvnw

# ソースコードをビルド
RUN ./mvnw clean package -DskipTests

# 最終イメージを作成
FROM mcr.microsoft.com/java/jre:8u192-zulu-alpine
RUN mkdir /app
WORKDIR /app
COPY --from=build /source/target/azrefarc-springboot-0.0.1-SNAPSHOT.jar /app/ROOT.jar

# コンテナのポート 8080 を外に解放する予定であることを示す
EXPOSE 8080

# コンテナ起動時の実行コマンドを指定
CMD ["java", "-jar", "/app/ROOT.jar"]
