package cz.siret.prank.program.routines

import cz.siret.prank.program.params.Params
import cz.siret.prank.program.params.RangeParam
import cz.siret.prank.program.routines.results.EvalResults
import cz.siret.prank.utils.Futils
import cz.siret.prank.utils.plotter.RPlotter
import groovy.transform.CompileDynamic
import groovy.transform.CompileStatic
import groovy.transform.TupleConstructor
import groovy.util.logging.Slf4j
import groovyx.gpars.GParsPool

import static cz.siret.prank.utils.ATimer.startTimer
import static cz.siret.prank.utils.Futils.mkdirs

/**
 * Routine for grid optimization. Loops through values of one or more RangeParam and produces resulting statistics and plots.
 */
@Slf4j
@CompileStatic
class ParamLooper extends Routine {

    List<RangeParam> rparams

    List<Step> steps

    String paramsTableFile
    String plotsDir
    String tablesDir

    Map<String, String> tables2D = new LinkedHashMap()

    ParamLooper(String outdir, List<RangeParam> rparams) {
        super(outdir)
        this.rparams = rparams
        plotsDir = "$outdir/plots"
    }

    /**
     * Iterate through al steps running closue.
     * Step is a particular assignment of flexible params, (e.g. "prram1=val1 param2=val2")
     * @param closure takes outdir as param
     *
     * TODO: merge with code in Experiments, there is no point in separation with closure
     */
    public void iterateSteps(Closure<EvalResults> closure) {
        def timer = startTimer()

        mkdirs(outdir)
        writeParams(outdir)

        String runsDir = "$outdir/runs"
        mkdirs(runsDir)

        steps = generateSteps()
        log.info "STEPS: " + steps.toListString().replace("Step","\nStep")

        paramsTableFile = "$outdir/param_stats.csv"
        PrintWriter tablef = Futils.getWriter paramsTableFile

        boolean doheader = true
        for (Step step in steps) {
            def stepTimer = startTimer()

            step.applyToParams(params)

            String stepDir = "$runsDir/$step.label"
            EvalResults res = closure.call(stepDir)     // execute an experiment in closure for a step

            step.results.putAll( res.stats )
            step.results.TIME_MINUTES = stepTimer.minutes

            if (doheader) {                             // use first step with results to produce header
                tablef << step.header + "\n";  doheader = false
            }
            tablef << step.toCSV() + "\n"; tablef.flush()

            if (paramsCount==2) {
                make2DTables(step)
            }
        }
        tablef.close()

        logTime "param iteration finished in $timer.formatted"
        write "results saved to directory [${Futils.absPath(outdir)}]"

        makePlots()

        if (params.ploop_delete_runs) {
            Futils.delete(runsDir)
        } else if (params.ploop_zip_runs) {
            Futils.zipAndDelete(runsDir, Futils.ZIP_BEST_COMPRESSION)
        }

        logTime "ploop routine finished in $timer.formatted"
    }

    private void make2DTables(Step step) {
        tablesDir = "$outdir/tables"
        tables2D = [:]
        step.results.each {
            make2DTable(it.key)
        }
    }

    int getParamsCount() {
        rparams.size()
    }

    private makePlots() {
        write "generating R plots..."
        mkdirs(plotsDir)
        if (paramsCount==1) {
            make1DPlots()
        } else if (paramsCount==2) {
            make2DPlots()
        }
    }

    private int getRThreads() {
        Math.min(params.threads, params.r_threads)
    }

    @CompileDynamic
    private make2DPlots() {
        GParsPool.withPool(RThreads) {
            tables2D.keySet().eachParallel { String key ->
                String value = tables2D.get(key)
                String label = key
                String fname = Futils.absSafePath(value)
                String labelX = rparams[1].name
                String labelY = rparams[0].name
                new RPlotter(plotsDir).plotHeatMapTable(fname, label, labelX, labelY)
            }
        }
    }

    private make1DPlots() {
        new RPlotter( paramsTableFile, plotsDir).plot1DAll(RThreads)
    }

    private make2DTable(String statName) {
        RangeParam paramX = rparams[0]
        RangeParam paramY = rparams[1]

        Map<List, Double> valueMap = new HashMap()
        for (Step s : steps) {
            def key = [ s.params[0].value, s.params[1].value ]
            valueMap.put( key, s.results.get(statName) )
        }

        StringBuilder sb = new StringBuilder()
        sb << "# resName \n"
        sb << "${paramX.name}/${paramY.name}," + paramY.values.collect { it }.join(",") + "\n"

        for (def va : paramX.values) {
            def row = paramY.values.collect { vb -> valueMap.get([va,vb]) }.collect { fmt it }.join(",")

            sb << "" + va + "," + row + "\n"
        }

        String fname = "$tablesDir/${statName}.csv"
        tables2D.put(statName, fname)
        Futils.writeFile fname, sb.toString()
    }

    private List<Step> generateSteps() {
        genStepsRecur(new ArrayList<Step>(), new Step(), rparams)
    }
    private List<Step> genStepsRecur(List<Step> steps, Step base, List<RangeParam> rparams) {
        if (rparams.empty) {
            steps.add(base); return
        }

        RangeParam rparam = rparams.head()
        for (Object val : rparam.values) {
            Step deeperStep = base.extendWith(rparam.name, val)
            genStepsRecur(steps, deeperStep, rparams.tail())
        }

        return steps
    }

    static String fmt(Double x) {
        if (x==null) return ""
        sprintf "%8.4f", x
    }

//===========================================================================================================//

    @TupleConstructor
    private static class Step {

        List<ParamVal> params = new ArrayList<>()
        Map<String, Double> results = new LinkedHashMap()

        void applyToParams(Params globalParams) {
            params.each { globalParams.setParam(it.name, it.value) }
        }

        Step extendWith(String pname, Object pval) {
            return new Step(params: params + new ParamVal(name: pname, value: pval))
        }

        String getLabel() {
            params.collect { it.name + "." + it.value }.join(".")
        }

        String getHeader() {
            (params*.name).join(',') + ',' + results.keySet().join(',')
        }

        @CompileDynamic
        String toCSV() {
            (params*.value).join(',') + ',' + results.values().collect{ fmt(it) }.join(',')
        }

        public String toString() {
            return "Step{${params.toListString()}, ${results.toMapString()}}";
        }
    }

    @TupleConstructor
    private static class ParamVal {
        String name
        Object value

        public String toString() {
            name + ":" + value
        }
    }

}
