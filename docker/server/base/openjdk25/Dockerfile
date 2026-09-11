#
# Copyright © 2016-2026 The Thingsboard Authors
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

# Official Debian image mirrored by Debian on AWS Public ECR. The digest pins
# the multi-platform Trixie Slim index used to build the local fallback image.
FROM public.ecr.aws/debian/debian:trixie-slim@sha256:ca17be52e5126dcb941b2508f265cf30cc86720dc605cc3eba13a6f4c49cd30a

ENV DEBIAN_FRONTEND=noninteractive

RUN apt-get update \
    && apt-get upgrade --yes \
    && apt-get install -y --no-install-recommends procps adduser \
    && apt-get autoremove --purge --yes \
    && apt-get clean \
    && rm -rf /var/lib/apt/lists/* \
    && addgroup --system thingsboard --gid=799 \
    && adduser --quiet \
        --system \
        --uid=799 \
        --ingroup thingsboard \
        --disabled-login \
        --disabled-password \
        --gecos "Thingsboard application" \
        thingsboard \
    && rm -rf /var/log/* \
    && chmod -R g+rwX /var/log \
    && mkdir -p /home/thingsboard \
    && chown thingsboard:thingsboard /home/thingsboard \
    && chmod -R g+rwX /home/thingsboard \
    && usermod -d /home/thingsboard thingsboard

ENV HOME=/home/thingsboard
ENV LANG=C.UTF-8
ENV JAVA_HOME=/docker-java-home
ENV JAVA_VERSION=25.0.4.1
ENV JAVA_DEBIAN_VERSION=25.0.4.1+1-1~deb13u1

RUN apt-get update \
    && apt-get install -y --no-install-recommends apt-transport-https ca-certificates \
    && { \
        echo '#!/bin/sh'; \
        echo 'set -e'; \
        echo; \
        echo 'dirname "$(dirname "$(readlink -f "$(which javac || which java)")")"'; \
    } > /usr/local/bin/docker-java-home \
    && chmod +x /usr/local/bin/docker-java-home \
    && ln -svT "/usr/lib/jvm/java-25-openjdk-$(dpkg --print-architecture)" /docker-java-home \
    && mkdir -p /usr/share/man/man1 \
    && apt-get install -y --no-install-recommends \
        openjdk-25-jdk-headless="${JAVA_DEBIAN_VERSION}" \
    && apt-get autoremove --purge --yes \
    && apt-get clean \
    && rm -rf /var/lib/apt/lists/* \
    && echo 'networkaddress.cache.ttl=60' >> /etc/java-25-openjdk/security/java.security \
    && test "$(readlink -f "${JAVA_HOME}")" = "$(docker-java-home)" \
    && test "$(echo 'System.out.println(System.getProperty("file.encoding"))' | jshell -s -)" = "UTF-8" \
    && rm -rf /home/thingsboard/.java \
    && test "$(id -u thingsboard)" = "799" \
    && test "$(id -g thingsboard)" = "799" \
    && javac -version \
    && java -version
