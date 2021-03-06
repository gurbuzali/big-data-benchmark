package com.hazelcast.benchmark.jet;

import com.hazelcast.jet.DAG;
import com.hazelcast.jet.Edge;
import com.hazelcast.jet.Jet;
import com.hazelcast.jet.JetInstance;
import com.hazelcast.jet.Vertex;
import com.hazelcast.jet.config.JobConfig;

import java.util.Map.Entry;
import java.util.StringTokenizer;

import static com.hazelcast.jet.Edge.between;
import static com.hazelcast.jet.KeyExtractors.entryKey;
import static com.hazelcast.jet.KeyExtractors.wholeItem;
import static com.hazelcast.jet.Partitioner.HASH_CODE;
import static com.hazelcast.jet.Processors.flatMap;
import static com.hazelcast.jet.Processors.groupAndAccumulate;
import static com.hazelcast.jet.connector.hadoop.ReadHdfsP.readHdfs;
import static com.hazelcast.jet.connector.hadoop.WriteHdfsP.writeHdfs;

public class JetWordCount {

    public static void main(String[] args) throws Exception {
        JetInstance client = Jet.newJetClient();

        String inputPath = args[0];
        String outputPath = args[1] + "_" + System.currentTimeMillis();

        DAG dag = new DAG();
        Vertex producer = dag.newVertex("reader", readHdfs(inputPath,
                (k, v) -> v.toString())).localParallelism(3);

        Vertex tokenizer = dag.newVertex("tokenizer",
                flatMap((String line) -> {
                    StringTokenizer s = new StringTokenizer(line);
                    return () -> s.hasMoreTokens() ? s.nextToken() : null;
                })
        );

        // word -> (word, count)
        Vertex accumulator = dag.newVertex("accumulator",
                groupAndAccumulate(() -> 0L, (count, x) -> count + 1)
        );

        // (word, count) -> (word, count)
        Vertex combiner = dag.newVertex("combiner",
                groupAndAccumulate(entryKey(), () -> 0L,
                        (Long count, Entry<String, Long> wordAndCount) -> count + wordAndCount.getValue())
        );
        Vertex consumer = dag.newVertex("writer", writeHdfs(outputPath)).localParallelism(1);

        dag.edge(Edge.between(producer, tokenizer))
           .edge(between(tokenizer, accumulator)
                   .partitioned(wholeItem(), HASH_CODE))
           .edge(between(accumulator, combiner)
                   .distributed()
                   .partitioned(entryKey()))
           .edge(Edge.between(combiner, consumer));

        JobConfig config = new JobConfig();
        config.addClass(JetWordCount.class);

        try {
            long start = System.currentTimeMillis();
            client.newJob(dag, config).execute().get();
            System.out.println("Time=" + (System.currentTimeMillis() - start));

        } finally {
            client.shutdown();
        }
    }
}
