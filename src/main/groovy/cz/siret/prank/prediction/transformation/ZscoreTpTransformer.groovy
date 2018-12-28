package cz.siret.prank.prediction.transformation

import com.google.gson.Gson
import com.google.gson.JsonElement
import cz.siret.prank.program.routines.results.Evaluation
import cz.siret.prank.utils.StatSample
import groovy.transform.CompileStatic

/**
 *  considers only true pockets
 */
@CompileStatic
class ZscoreTpTransformer extends ScoreTransformer {

    double mean = 0
    double stdev = 0

    @Override
    double transformScore(double rawScore) {
        (rawScore - mean) / stdev
    }

    void doTrain(List<Double> scores) {
        StatSample sample = new StatSample(scores)
        mean = sample.mean
        stdev = sample.stddev
    }

    @Override
    void trainForPockets(Evaluation evaluation) {
        List<Double> tpScores = (List<Double>) evaluation.pocketRows.findAll { it.isTruePocket() }.collect { it.score }
        doTrain(tpScores)
    }

    @Override
    JsonElement toJson() {
        new Gson().toJsonTree(this)
    }

    @Override
    ScoreTransformer loadFromJson(JsonElement json) {
        new Gson().fromJson(json, ZscoreTpTransformer.class)
    }



}
