/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.search.profile;

import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.search.Collector;
import org.apache.lucene.search.LeafCollector;
import org.elasticsearch.common.ParseField;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.io.stream.Streamable;
import org.elasticsearch.common.xcontent.ToXContent;
import org.elasticsearch.common.xcontent.XContentBuilder;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * This class wraps a Lucene Collector and times the execution of:
 * - setScorer()
 * - collect()
 * - doSetNextReader()
 * - needsScores()
 *
 * Because Collectors are (relatively) simple, this class also acts as the repository
 * for timing values and is the class that is serialized between nodes after the search
 * is complete.  When the InternalProfileCollector is serialized, only the timing
 * information is sent (not the wrapped Collector), so it cannot be used for another
 * search
 *
 * InternalProfiler facilitates the linking of the the Collector graph
 */
public class InternalProfileCollector implements Collector, CollectorResult, ToXContent, Streamable {

    private static final ParseField NAME = new ParseField("name");
    private static final ParseField REASON = new ParseField("reason");
    private static final ParseField TIME = new ParseField("time");
    private static final ParseField CHILDREN = new ParseField("children");

    /**
     * A more friendly representation of the Collector's class name
     */
    private String collectorName;

    /**
     * A "hint" to help provide some context about this Collector
     */
    private String reason;

    /** The wrapped collector, or null when deserializing. */
    private final ProfileCollector collector;

    /**
     * The total elapsed time for this Collector, only relevant if
     * {@code collector} is null, otherwise you need to read
     * {@code collector.getTime()}.
     */
    private Long time;

    /**
     * The total elapsed time for all Collectors across all shards.  This is
     * only populated after the query has finished and timings are finalized
     */
    private long globalTime;

    private List<InternalProfileCollector> children;

    public InternalProfileCollector(Collector collector, String reason, List<InternalProfileCollector> children) {
        this.collector = new ProfileCollector(collector);
        this.reason = reason;
        this.collectorName = deriveCollectorName(collector);
        this.children = children;
    }

    /** For serialization. */
    public InternalProfileCollector() {
        this.collector = null;
    }

    /**
     * Creates a human-friendly representation of the Collector name.
     *
     * Bucket Collectors use the aggregation name in their toString() method,
     * which makes the profiled output a bit nicer.
     *
     * @param c The Collector to derive a name from
     * @return  A (hopefully) prettier name
     */
    private String deriveCollectorName(Collector c) {
        String s = c.getClass().getSimpleName();

        // MutiCollector which wraps multiple BucketCollectors is generated
        // via an anonymous class, so this corrects the lack of a name by
        // asking the enclosingClass
        if (s.equals("")) {
            s = c.getClass().getEnclosingClass().getSimpleName();
        }

        // Aggregation collector toString()'s include the user-defined agg name
        if (reason.equals(REASON_AGGREGATION) || reason.equals(REASON_AGGREGATION_GLOBAL)) {
            s += ": [" + c.toString() + "]";
        }
        return s;
    }

    @Override
    public LeafCollector getLeafCollector(LeafReaderContext context) throws IOException {
        return collector.getLeafCollector(context);
    }

    @Override
    public boolean needsScores() {
        return collector.needsScores();
    }

    /**
     * Returns the reason "hint"
     */
    @Override
    public String getReason() {
        return reason;
    }

    /**
     * Returns the elapsed time for this Collector, inclusive of children
     */
    @Override
    public long getTime() {
        if (collector == null) {
            return time;
        } else {
            assert time == null;
            return collector.getTime();
        }
    }

    @Override
    public List<CollectorResult> getProfiledChildren() {
        return Collections.unmodifiableList(children);
    }
    @Override
    public String getName() {
        return collectorName;
    }

    @Override
    public String toString() {
        return getName();
    }

    @Override
    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        builder = builder.startObject()
                .field(NAME.getPreferredName(), toString())
                .field(REASON.getPreferredName(), reason.toString())
                .field(TIME.getPreferredName(), String.format(Locale.US, "%.10gms", (double) (getTime() / 1000000.0)));

        if (children.isEmpty() == false) {
            builder = builder.startArray(CHILDREN.getPreferredName());
            for (InternalProfileCollector child : children) {
                builder = child.toXContent(builder, params);
            }
            builder = builder.endArray();
        }
        builder = builder.endObject();
        return builder;
    }

    @Override
    public void readFrom(StreamInput in) throws IOException {
        collectorName = in.readString();
        reason = in.readString();
        time = in.readLong();
        globalTime = in.readLong();
        int size = in.readVInt();
        children = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            InternalProfileCollector child = readProfileCollectorFromStream(in);
            children.add(child);
        }
    }

    public static InternalProfileCollector readProfileCollectorFromStream(StreamInput in) throws IOException {
        InternalProfileCollector newInternalProfileCollector = new InternalProfileCollector();
        newInternalProfileCollector.readFrom(in);
        return newInternalProfileCollector;
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        out.writeString(collectorName);
        out.writeString(reason);
        if (collector != null) {
            out.writeLong(collector.getTime());
        } else {
            assert time != null;
            out.writeLong(time);
        }
        out.writeLong(globalTime);
        out.writeVInt(children.size());
        for (InternalProfileCollector child : children) {
            child.writeTo(out);
        }
    }
}