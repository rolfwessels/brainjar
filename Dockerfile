# glibc-based image: ONNX Runtime used by langchain4j embeddings ships a
# glibc-linked libonnxruntime.so that does not run on musl/alpine.
FROM eclipse-temurin:21-jdk-noble

ENV DEBIAN_FRONTEND=noninteractive

RUN apt-get update \
  && apt-get upgrade -y \
  && apt-get install -y --no-install-recommends \
    ca-certificates \
    git \
    curl \
    wget \
    zip \
    bash \
    make \
    rsync \
    nano \
    zsh \
    openssh-client \
    docker.io \
    docker-compose-v2 \
  && update-ca-certificates \
  && rm -rf /var/lib/apt/lists/* \
  && git config --global --add safe.directory /brain-jar

RUN sh -c "$(curl -fsSL https://raw.githubusercontent.com/ohmyzsh/ohmyzsh/master/tools/install.sh)" && \
  git clone https://github.com/zsh-users/zsh-autosuggestions.git /root/.oh-my-zsh/plugins/zsh-autosuggestions && \
  git clone https://github.com/zsh-users/zsh-autosuggestions /root/.oh-my-zsh/custom/plugins/zsh-autosuggestions && \
  echo "done"

WORKDIR /brain-jar
COPY ["build.gradle.kts", "settings.gradle.kts", "gradle.properties", "./"]
COPY ["gradle/", "gradle/"]
COPY ["gradlew", "./"]
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies

ENV TERM=xterm-256color
RUN printf 'export PS1="\[\e[0;34;0;33m\][DCKR]\[\e[0m\] \\t \[\e[40;38;5;28m\][\w]\[\e[0m\] \$ "' >> ~/.bashrc
