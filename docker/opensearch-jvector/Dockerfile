ARG OPENSEARCH_VERSION=3.8.0
FROM opensearchproject/opensearch:${OPENSEARCH_VERSION}
ARG OPENSEARCH_VERSION

RUN if bin/opensearch-plugin list | grep -qx opensearch-neural-search; then bin/opensearch-plugin remove opensearch-neural-search; fi \
    && if bin/opensearch-plugin list | grep -qx opensearch-knn; then bin/opensearch-plugin remove opensearch-knn; fi \
    && bin/opensearch-plugin install --batch org.opensearch.plugin:opensearch-jvector-plugin:${OPENSEARCH_VERSION}.0
