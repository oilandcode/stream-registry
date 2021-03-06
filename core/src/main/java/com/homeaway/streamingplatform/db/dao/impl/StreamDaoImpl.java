/* Copyright (c) 2018 Expedia Group.
 * All rights reserved.  http://www.homeaway.com

 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at

 *      http://www.apache.org/licenses/LICENSE-2.0

 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.homeaway.streamingplatform.db.dao.impl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

import javax.ws.rs.InternalServerErrorException;

import lombok.extern.slf4j.Slf4j;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.kafka.clients.producer.ProducerConfig;

import com.homeaway.digitalplatform.streamregistry.AvroStream;
import com.homeaway.digitalplatform.streamregistry.AvroStreamKey;
import com.homeaway.digitalplatform.streamregistry.ClusterValue;
import com.homeaway.digitalplatform.streamregistry.OperationType;
import com.homeaway.streamingplatform.configuration.KafkaProducerConfig;
import com.homeaway.streamingplatform.db.dao.AbstractDao;
import com.homeaway.streamingplatform.db.dao.KafkaManager;
import com.homeaway.streamingplatform.db.dao.RegionDao;
import com.homeaway.streamingplatform.db.dao.StreamDao;
import com.homeaway.streamingplatform.dto.AvroToJsonDTO;
import com.homeaway.streamingplatform.dto.JsonToAvroDTO;
import com.homeaway.streamingplatform.exceptions.InvalidStreamException;
import com.homeaway.streamingplatform.exceptions.StreamCreationException;
import com.homeaway.streamingplatform.exceptions.StreamNotFoundException;
import com.homeaway.streamingplatform.extensions.validation.StreamValidator;
import com.homeaway.streamingplatform.model.Stream;
import com.homeaway.streamingplatform.model.Tags;
import com.homeaway.streamingplatform.provider.InfraManager;
import com.homeaway.streamingplatform.streams.ManagedKStreams;
import com.homeaway.streamingplatform.streams.ManagedKafkaProducer;

@Slf4j
public class StreamDaoImpl extends AbstractDao implements StreamDao, StreamValidator {

    private StreamValidator streamValidator;

    public StreamDaoImpl(ManagedKafkaProducer managedKafkaProducer,
        ManagedKStreams kStreams,
        String env,
        RegionDao regionDao,
        InfraManager infraManager,
        KafkaManager kafkaManager,
        StreamValidator validator) {
        super(managedKafkaProducer, kStreams, env, regionDao, infraManager, kafkaManager);
        this.streamValidator = validator;
    }

    // TODO - This stream validation pattern needs to be reimplemented
    @Override
    public boolean isStreamValid(Stream stream) throws InvalidStreamException {
        try {
            if (streamValidator != null && !streamValidator.isStreamValid(stream)) {
                throw new InvalidStreamException(stream, streamValidator.getValidationAssertion());
            }

            if (stream.getName() == null) {
                throw new InvalidStreamException(stream, "Stream name can not be null");
            }

            /*
             * TODO: Determine if streams actually need a list of VPCs defined.
             * For example, single cluster or single region deployments, this property is superfluous.
             * Instead, there should be a way to specify a singular default.
             */
            if (stream.getVpcList() == null || stream.getVpcList().isEmpty()) {
                String err = String.format("Stream %s can not be created without vpcList configuration", stream.getName());
                throw new InvalidStreamException(stream, err);
            }
        } catch (InvalidStreamException e) {
            String err = String.format("Stream %s cannot be validated.", stream.getName());
            String msg = e.getMessage();
            if (msg == null || msg.trim().isEmpty()) {
                log.error(err);
                throw new InvalidStreamException(stream);
            } else {
                log.error(err + " " + msg);
                throw new InvalidStreamException(stream, msg);
            }
        }
        return true;
    }

    @Override
    public String getValidationAssertion() {
        return null; // unused
    }

    @Override
    public void configure(Map<String, ?> configs) {}

    @Override
    public void upsertStream(Stream stream) {
        applyDefaultHint(stream);
        applyDefaultPartition(stream);
        applyDefaultReplicationFactor(stream);

        // TODO Can isStreamValid (as currently implemented) ever be false?
        if (!isStreamValid(stream)) {
            log.error("Stream '{}' is not valid", stream.getName());
        }

        Pair<AvroStreamKey, Optional<AvroStream>> keyValue = getAvroStreamKeyValue(stream.getName());
        try {
            AvroStreamKey key;
            AvroStream avroStream =
                JsonToAvroDTO.convertJsonToAvro(stream, OperationType.UPSERT);

            Optional<AvroStream> value = keyValue.getValue();
            if (value.isPresent()) {
                // ensure valid partition counts and replication factors are provided
                if (stream.getPartitions() != value.get().getPartitions()) {
                    throw new IllegalArgumentException(String.format(
                            "Stream registry does not currently support modifying the initial partition count. " +
                                    "Requested: %s. Current count: %s", stream.getPartitions(),
                            value.get().getPartitions()));
                } else if (stream.getReplicationFactor() != value.get().getReplicationFactor()) {
                    throw new IllegalArgumentException(String.format(
                            "Stream registry does not currently support modifying the initial replication factor. " +
                                    "Requested: %s. Current replication factor: %s", stream.getReplicationFactor(),
                            value.get().getReplicationFactor()));
                }
                key = keyValue.getKey();
                log.info("key={} available for the stream-name={}", key, stream.getName());
                // Copy the earlier stream properties
                avroStream.setCreated(value.get().getCreated());
                avroStream.setProducers(value.get().getProducers());
                avroStream.setConsumers(value.get().getConsumers());
                avroStream.setRegionReplicatorList(value.get().getRegionReplicatorList());
                avroStream.setS3ConnectorList(value.get().getS3ConnectorList());
            } else {
                log.info("key NOT available for the stream-name={}", stream.getName());
                avroStream.setCreated(System.currentTimeMillis());
                key = AvroStreamKey.newBuilder().setStreamName(avroStream.getName()).build();
            }
            verifyAndUpsertTopics(stream);
            kafkaProducer.log(key, avroStream);
            log.info("Stream upserted for {}", stream.getName());
        } catch (IllegalArgumentException e) {
            log.error("caught an illegal argument exception");
            throw new IllegalArgumentException(e);
        } catch (Exception e) {
            log.error("Error creating new stream", e);
            throw new StreamCreationException(stream.getName());
        }
    }

    /**
     * Setting default replication factor in case if its set to 0 or a negative number
     * @param stream the stream that will be given a default replication factor
     */
    private void applyDefaultReplicationFactor(Stream stream) {
        if (stream.getReplicationFactor() <= 0) {
            stream.setReplicationFactor(3);
        }
    }

    /**
     * Setting default partition in case if its set to 0 or a negative number
     * @param stream the stream that will be given a default number of partitions
     */
    private void applyDefaultPartition(Stream stream) {
        if (stream.getPartitions() <= 0) {
            stream.setPartitions(1);
        }
    }

    /**
     * Verify if the Topic exists If not, create Topics in the Cluster
     *
     * @param stream the stream that will be used to verify and/or upsert topics to
     */
    private void verifyAndUpsertTopics(Stream stream) {
        List<String> vpcList = stream.getVpcList();
        List<String> replicatedVpcList = stream.getReplicatedVpcList();
        try {
            log.info("creating topics for vpcList: {}", vpcList);
            for (String vpc : vpcList) {
                upsertTopics(stream, vpc);
            }
            if (replicatedVpcList != null && !replicatedVpcList.isEmpty()) {
                log.info("creating topics for replicatedVpcList: {}", replicatedVpcList);
                for (String vpc : replicatedVpcList) {
                    upsertTopics(stream, vpc);
                }
            }
        } catch (Exception e) {
            throw new InternalServerErrorException(
                String.format("Error while creating the stream. Can't create the topic %s", stream.getName()), e);
        }
    }

    private void upsertTopics(Stream stream, String vpc) {
        ClusterValue clusterValue = getClusterDetails(vpc, env, stream.getTags().getHint(), "producer");
        Properties topicConfig = new Properties();
        if (stream.getTopicConfig() != null) {
            topicConfig.putAll(stream.getTopicConfig());
        }

        String bootstrapServer = clusterValue.getClusterProperties().get(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG);
        String zkConnect = clusterValue.getClusterProperties().get(KafkaProducerConfig.ZOOKEEPER_QUORUM);
        topicConfig.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServer);
        topicConfig.put(KafkaProducerConfig.ZOOKEEPER_QUORUM, zkConnect);

        kafkaManager.upsertTopics(Collections.singleton(stream.getName()), stream.getPartitions(), stream.getReplicationFactor(), topicConfig);
        log.info("Topic {} created/updated at {}",stream.getName(), bootstrapServer);
    }

    private void applyDefaultHint(Stream stream) {
        Tags tags = stream.getTags();

        String hint = tags.getHint();

        if (hint == null || hint.trim().matches("(?i:string)?")) {
            hint = AbstractDao.PRIMARY_HINT;
        }

        tags.setHint(hint);
    }

    @Override
    public Optional<Stream> getStream(String streamName) {
        log.info("Pulling stream information from global state-store for streamName={}", streamName);
        Optional<AvroStream> streamValue = kStreams.getAvroStreamForKey(AvroStreamKey.newBuilder().setStreamName(streamName).build());
        return streamValue.map(AvroToJsonDTO::convertAvroToJson);
    }

    @Override
    public void deleteStream(String streamName) {
        Pair<AvroStreamKey, Optional<AvroStream>> keyValue = getAvroStreamKeyValue(streamName);

        if (!keyValue.getValue().isPresent()) {
            throw new StreamNotFoundException(streamName);
        }
        try {
            kafkaProducer.log(keyValue.getKey(), null);
        } catch (Exception e) {
            log.error("Error deleting stream", e);
        }
    }

    @Override
    public List<Stream> getAllStreams() {
        List<Stream> streamList = new ArrayList<>();
        log.info("Pulling stream information from local instance's state-store");
        kStreams.getAllStreams().forEachRemaining(avroStream -> streamList.add(AvroToJsonDTO.convertAvroToJson(avroStream.value)));
        return streamList;
    }

    public void updateAvroStream(AvroStream stream) {
        AvroStreamKey key = AvroStreamKey.newBuilder().setStreamName(stream.getName()).build();
        stream.setOperationType(OperationType.UPSERT);
        try {
            kafkaProducer.log(key, stream);
        } catch (Exception e) {
            log.error("Error logging stream - {}", stream, e);
        }
    }
}
