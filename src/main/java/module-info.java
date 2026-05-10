module cloudsimplus {
    requires ch.qos.logback.classic;
    requires com.google.gson;
    requires commons.math3;
    requires static lombok;
    requires org.apache.commons.collections4;
    requires org.apache.commons.lang3;
    requires org.jetbrains.annotations;
    requires org.slf4j;

    // Cloud-native services package: services.config (file-based registration)
    // and services.reporting (Grafana / CSV writers) use Jackson.
    requires com.fasterxml.jackson.annotation;
    requires com.fasterxml.jackson.databind;
    requires com.fasterxml.jackson.dataformat.yaml;
}
