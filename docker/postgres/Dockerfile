FROM pgvector/pgvector:0.6.0-pg15

# SCWS — Chinese word segmentation library (required by zhparser)
RUN apt-get update && apt-get install -y --no-install-recommends \
    build-essential \
    git \
    postgresql-server-dev-15 \
    wget \
    && rm -rf /var/lib/apt/lists/*

RUN wget -q -O /tmp/scws.tar.bz2 http://www.xunsearch.com/scws/down/scws-1.2.3.tar.bz2 \
    && cd /tmp \
    && tar xjf scws.tar.bz2 \
    && cd /tmp/scws-1.2.3 \
    && ./configure \
    && make \
    && make install \
    && ldconfig \
    && rm -rf /tmp/scws*

# zhparser — PostgreSQL extension for Chinese text segmentation
RUN git clone https://github.com/amutu/zhparser.git /tmp/zhparser \
    && cd /tmp/zhparser \
    && SCWS_HOME=/usr/local make \
    && make install \
    && rm -rf /tmp/zhparser