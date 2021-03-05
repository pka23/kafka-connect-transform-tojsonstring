/*
 * Copyright © 2021 Christian Edelsbrunner (christian.edelsbrunner@gmail.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.github.cedelsb.kafka.connect.smt;

import at.grahsl.kafka.connect.mongodb.converter.AvroJsonSchemafulRecordConverter;
import org.apache.kafka.common.config.ConfigDef;
import org.apache.kafka.connect.connector.ConnectRecord;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;

import org.apache.kafka.connect.errors.DataException;
import org.apache.kafka.connect.transforms.Transformation;
import org.apache.kafka.connect.transforms.util.SimpleConfig;
import org.bson.BsonDocument;
import org.bson.json.JsonMode;
import org.bson.json.JsonWriterSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

import static org.apache.kafka.connect.transforms.util.Requirements.requireStruct;

public abstract class Record2JsonStringConverter<R extends ConnectRecord<R>> implements Transformation<R> {

    private static Logger logger = LoggerFactory.getLogger(Record2JsonStringConverter.class);

    public static final String OVERVIEW_DOC =
            "Converts a record value with a schema into a new schema containing a single JSON string field";

    private interface ConfigName {
        String JSON_STRING_FIELD_NAME = "json.string.field.name";
        String JSON_WRITER_OUTPUT_MODE = "json.writer.output.mode";
    }

    public static final ConfigDef CONFIG_DEF = new ConfigDef()
            .define(ConfigName.JSON_STRING_FIELD_NAME, ConfigDef.Type.STRING, "jsonstring", ConfigDef.Importance.HIGH,
                    "Field name for output JSON String field")
            .define(ConfigName.JSON_WRITER_OUTPUT_MODE, ConfigDef.Type.STRING, "RELAXED", ConfigDef.Importance.MEDIUM,
                    "Output mode of JSON Writer (RELAXED,EXTENDED,SHELL or STRICT)");

    private static final String PURPOSE = "Converting record with Schema into a simple JSON String";

    private String jsonStringFieldName;
    private Schema jsonStringOutputSchema;

    AvroJsonSchemafulRecordConverter converter;
    JsonWriterSettings jsonWriterSettings;

    @Override
    public void configure(Map<String, ?> props) {
        final SimpleConfig config = new SimpleConfig(CONFIG_DEF, props);
        jsonStringFieldName = config.getString(ConfigName.JSON_STRING_FIELD_NAME);
        jsonStringOutputSchema = makeJsonStringOutputSchema();

        jsonWriterSettings = JsonWriterSettings
                .builder()
                .outputMode(toJsonMode(config.getString(ConfigName.JSON_WRITER_OUTPUT_MODE)))
                .build();


        converter = new AvroJsonSchemafulRecordConverter();
    }

    @Override
    public R apply(R record) {

        if (isTombstoneRecord(record))
            return record;

        if (operatingSchema(record) == null) {
            logger.info("toJsontransformation is ignoring value/key without schema");
            return record;
        }

        return applyWithSchema(record);

    }


    private R applyWithSchema(R record) {

        final Struct value = requireStruct(operatingValue(record), PURPOSE);

        Schema schema = operatingSchema(record);

        BsonDocument bsonDoc = converter.convert(schema, value);

        final Struct jsonStringOutputStruct = new Struct(jsonStringOutputSchema);
        jsonStringOutputStruct.put(jsonStringFieldName, bsonDoc.toJson(jsonWriterSettings));

        return newRecord(record, jsonStringOutputSchema, jsonStringOutputStruct);
    }

    @Override
    public ConfigDef config() {
        return CONFIG_DEF;
    }


    @Override
    public void close() {
        converter = null;
    }

    private Schema makeJsonStringOutputSchema() {
        return SchemaBuilder
                .struct()
                .name("jsonStringSchema")
                .version(1)
                .field(jsonStringFieldName, Schema.STRING_SCHEMA)
                .build();
    }

    private JsonMode toJsonMode(String jsonMode)
    {
        switch(jsonMode)
        {
            case "SHELL":
                return JsonMode.SHELL;
            case "EXTENDED":
                return JsonMode.EXTENDED;
            case "STRICT":
                return JsonMode.STRICT;
            default:
                return JsonMode.RELAXED;
        }
    }

    protected abstract Schema operatingSchema(R record);

    protected abstract Object operatingValue(R record);

    protected abstract R newRecord(R record, Schema updatedSchema, Object updatedValue);

    protected abstract boolean isTombstoneRecord(R record);

    public static class Key<R extends ConnectRecord<R>> extends Record2JsonStringConverter<R> {

        @Override
        protected Schema operatingSchema(R record) {

            return record.keySchema();
        }

        @Override
        protected Object operatingValue(R record) {

            return record.key();
        }

        @Override
        protected R newRecord(R record, Schema updatedSchema, Object updatedValue) {
            return record.newRecord(record.topic(), record.kafkaPartition(), updatedSchema, updatedValue, record.valueSchema(), record.value(), record.timestamp());
        }

        @Override
        protected boolean isTombstoneRecord(R record) {
            return record.key() == null;
        }

    }

    public static class Value<R extends ConnectRecord<R>> extends Record2JsonStringConverter<R> {

        @Override
        protected Schema operatingSchema(R record) {
            return record.valueSchema();
        }

        @Override
        protected Object operatingValue(R record) {
            return record.value();
        }

        @Override
        protected R newRecord(R record, Schema updatedSchema, Object updatedValue) {
            return record.newRecord(record.topic(), record.kafkaPartition(), record.keySchema(), record.key(), updatedSchema, updatedValue, record.timestamp());
        }

        @Override
        protected boolean isTombstoneRecord(R record) {
            return record.value() == null;
        }

    }
}
