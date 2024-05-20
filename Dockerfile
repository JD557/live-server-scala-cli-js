FROM mcr.microsoft.com/devcontainers/base:ubuntu

# Install OpenJDK 11
RUN apt-get update && \
    apt-get install -y openjdk-17-jdk && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/*

# https://get-coursier.io/docs/cli-installation
RUN curl -fL "https://github.com/coursier/launchers/raw/master/cs-x86_64-pc-linux.gz" | gzip -d > cs && chmod +x cs && mv cs /usr/local/bin/cs

COPY build.sc .
COPY mill .
COPY .mill-version .
COPY playwrightVersion.sc .

# Install these extensions
RUN code-server --install-extension scalameta.metals \
  && code-server --install-extension usernamehw.errorlens \
  && code-server --install-extension vscjava.vscode-java-pack \
  && code-server --install-extension github.copilot \
  && code-server --install-extension github.copilot-chat \
  && code-server --install-extension github.vscode-github-actions \
  && code-server --install-extension github.vscode-pull-request-github \
  && code-server --install-extension eamodio.gitlens \
  && code-server --install-extension ms-vscode-remote.remote-containers \
  && code-server --install-extension github.vscode-pull-request-github

# Download mills dependancies. if build.sc hasn't changed, this _should_ hit the layer cache.
RUN ./mill __.prepareOffline

# We should have all the deps to run Metals here, saving time from a cold start.
RUN cs install metals
# Copy source into container
COPY . .

# Compile the project - anything that has hit "main" should (at least!) compile
# And setup mills BSP server for metals
RUN ./mill show __.compile && ./mill mill.bsp.BSP/install