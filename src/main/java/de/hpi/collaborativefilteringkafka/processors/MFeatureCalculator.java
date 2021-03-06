package de.hpi.collaborativefilteringkafka.processors;

import de.hpi.collaborativefilteringkafka.apps.ALSApp;
import de.hpi.collaborativefilteringkafka.messages.FeatureMessage;
import org.apache.kafka.streams.processor.AbstractProcessor;
import org.apache.kafka.streams.processor.ProcessorContext;
import org.apache.kafka.streams.processor.PunctuationType;
import org.apache.kafka.streams.processor.To;
import org.apache.kafka.streams.state.KeyValueStore;
import org.ejml.data.FMatrixRMaj;
import org.ejml.dense.row.CommonOps_FDRM;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.stream.Collectors;

public class MFeatureCalculator extends AbstractProcessor<Integer, FeatureMessage> {
    private ProcessorContext context;
    private KeyValueStore<Integer, ArrayList<Integer>> mInBlocksUidStore;
    private KeyValueStore<Integer, ArrayList<Short>> mInBlocksRatingsStore;
    private KeyValueStore<Integer, ArrayList<Short>> mOutBlocksStore;
    private HashMap<Integer, HashMap<Integer, float[]>> movieIdToUserFeatureVectors;
    private long currentMatrixOpTimeAgg;
    private boolean hasAlreadyPrintedTime;

    @Override
    @SuppressWarnings("unchecked")
    public void init(final ProcessorContext context) {
        this.context = context;

        this.mInBlocksUidStore = (KeyValueStore<Integer, ArrayList<Integer>>) this.context.getStateStore(ALSApp.M_INBLOCKS_UID_STORE);
        this.mInBlocksRatingsStore = (KeyValueStore<Integer, ArrayList<Short>>) this.context.getStateStore(ALSApp.M_INBLOCKS_RATINGS_STORE);
        this.mOutBlocksStore = (KeyValueStore<Integer, ArrayList<Short>>) this.context.getStateStore(ALSApp.M_OUTBLOCKS_STORE);

        this.movieIdToUserFeatureVectors = new HashMap<>();
        this.currentMatrixOpTimeAgg = 0L;
        this.hasAlreadyPrintedTime = false;

        this.context.schedule(Duration.ofSeconds(60), PunctuationType.WALL_CLOCK_TIME, timestamp -> {
            if (!this.hasAlreadyPrintedTime && !this.movieIdToUserFeatureVectors.isEmpty()) {
                System.out.println(String.format("MFeatCalc, partition: %d, time spent on matrix stuff: %d", this.context.partition(), this.currentMatrixOpTimeAgg));
                this.hasAlreadyPrintedTime = true;
            }
        });
    }

    @Override
    public void process(final Integer partition, final FeatureMessage msg) {
        long before = System.currentTimeMillis();

        int userIdForFeatures = msg.id;
        ArrayList<Integer> movieIds = msg.dependentIds;
        float[] features = msg.features;

        for (int movieId : movieIds) {
            ArrayList<Integer> inBlockUidsForM = this.mInBlocksUidStore.get(movieId);
            HashMap<Integer, float[]> userIdToFeature = movieIdToUserFeatureVectors.get(movieId);
            if (userIdToFeature == null) {
                userIdToFeature = new HashMap<>();
            }
            userIdToFeature.put(userIdForFeatures, features);
            movieIdToUserFeatureVectors.put(movieId, userIdToFeature);

            if (userIdToFeature.size() == inBlockUidsForM.size()) {  // everything necessary for movie feature calculation has been received
                float[][] uFeatures = new float[inBlockUidsForM.size()][ALSApp.NUM_FEATURES];
                int i = 0;
                for (Integer userId : inBlockUidsForM) {
                    uFeatures[i] = userIdToFeature.get(userId);
                    i++;
                }

                // user features matrix ordered by userid (rows) with ALSApp.NUM_FEATURES features (cols)
                FMatrixRMaj uFeaturesMatrix = new FMatrixRMaj(uFeatures);

                ArrayList<Short> movieIdRatingsList = this.mInBlocksRatingsStore.get(movieId);

                float[][] movieIdRatingsArray = new float[movieIdRatingsList.size()][1];
                for (int k = 0; k < movieIdRatingsArray.length; k++) {
                    movieIdRatingsArray[k][0] = (float) movieIdRatingsList.get(k);
                }

                FMatrixRMaj movieIdRatingsVector = new FMatrixRMaj(movieIdRatingsArray);

                FMatrixRMaj V = new FMatrixRMaj(ALSApp.NUM_FEATURES, 1);
                CommonOps_FDRM.multTransA(uFeaturesMatrix, movieIdRatingsVector, V);

                FMatrixRMaj A = new FMatrixRMaj(ALSApp.NUM_FEATURES, ALSApp.NUM_FEATURES);
                CommonOps_FDRM.multTransA(uFeaturesMatrix, uFeaturesMatrix, A);

                FMatrixRMaj normalization = new FMatrixRMaj(ALSApp.NUM_FEATURES, ALSApp.NUM_FEATURES);
                CommonOps_FDRM.scale((float) movieIdRatingsArray.length, CommonOps_FDRM.identity(ALSApp.NUM_FEATURES), normalization);

                FMatrixRMaj newA = new FMatrixRMaj(ALSApp.NUM_FEATURES, ALSApp.NUM_FEATURES);
                CommonOps_FDRM.add(A, ALSApp.ALS_LAMBDA, normalization, newA);

                FMatrixRMaj mFeaturesVector = new FMatrixRMaj(ALSApp.NUM_FEATURES, 1);
                CommonOps_FDRM.invert(newA);
                CommonOps_FDRM.mult(newA, V, mFeaturesVector);

                float[] mFeaturesVectorFloat = new float[ALSApp.NUM_FEATURES];
                for (int l = 0; l < ALSApp.NUM_FEATURES; l++) {
                    mFeaturesVectorFloat[l] = mFeaturesVector.get(l, 0);
                }

                String sourceTopic = this.context.topic();
                int sourceTopicIteration = Integer.parseInt(sourceTopic.substring(sourceTopic.length() - 1));
                int sinkTopicIteration = sourceTopicIteration;

                ArrayList<Integer> dependentUids = this.mInBlocksUidStore.get(movieId);
                FeatureMessage featureMsgToBeSent = new FeatureMessage(
                        movieId,
                        dependentUids,
                        mFeaturesVectorFloat
                );

                if (sourceTopicIteration == ALSApp.NUM_ALS_ITERATIONS - 1) {
                    context.forward(
                            0,
                            featureMsgToBeSent,
                            To.child(ALSApp.MOVIE_FEATURES_SINK + ALSApp.NUM_ALS_ITERATIONS)
                    );
                }

                for (int targetPartition : this.mOutBlocksStore.get(movieId)) {
                    featureMsgToBeSent.setDependentIds((ArrayList<Integer>) dependentUids.stream().filter(id -> (id % ALSApp.NUM_PARTITIONS) == targetPartition).collect(Collectors.toList()));
                    context.forward(
                            targetPartition,
                            featureMsgToBeSent,
                            To.child(ALSApp.MOVIE_FEATURES_SINK + sinkTopicIteration)
                    );
                }
            }
        }
        this.currentMatrixOpTimeAgg += (System.currentTimeMillis() - before);
    }

    @Override
    public void close() {}
}