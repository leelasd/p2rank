package cz.siret.prank.program.rendering

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import org.biojava.nbio.structure.Atom
import cz.siret.prank.domain.Dataset
import cz.siret.prank.domain.Pocket
import cz.siret.prank.domain.PredictionPair
import cz.siret.prank.program.params.Parametrized
import cz.siret.prank.score.WekaSumRescorer
import cz.siret.prank.utils.ColorUtils
import cz.siret.prank.utils.futils

import java.awt.*
import java.util.List

@Slf4j
@CompileStatic
class PyMolRenderer implements Parametrized {

    String outdir

    PyMolRenderer(String outputDir) {
        this.outdir = outputDir
    }

    static String pyColor(Color c) {
        sprintf "[%5.3f,%5.3f,%5.3f]", c.red/255, c.green/255, c.blue/255
    }

    private String colorPocketSurfaces(PredictionPair pair) {
        String res = ""

        int N = pair.prediction.reorderedPockets.size()

        List<Color> colors = ColorUtils.createSpectrum(N, 0.6, 0.6, 1.20)


        int i = 1;
        pair.prediction.reorderedPockets.each { Pocket pocket ->
            String ids = pocket.surfaceAtoms.list.collect {it.PDBserial}.join(",")
            String name = "surf_pocket$i"
            String ncol = "pcol$i"

            res += "set_color $ncol = " + pyColor(colors[i-1]) + "\n"
            res += "select $name, protein and id [$ids] \n"
            res += "set surface_color,  $ncol, $name \n"
            i++
        }

        return res
    }

    void visualizeHistograms(Dataset.Item item, WekaSumRescorer rescorer, PredictionPair pair) {

        String label = item.label

        String pmlf = "$outdir/${label}.pml"
        String pointsDir = "$outdir/data"

        futils.mkdirs(pointsDir)

        String pointsf = "$pointsDir/${label}_points.pdb"
        String pointsfRelName = "data/${label}_points.pdb"
        String pointsf0 = "$pointsDir/${label}_points0.pdb"
        String pointsf0RelName = "data/${label}_points0.pdb"

        String proteinf = futils.absPath(item.proteinFile)
        if (params.vis_copy_proteins) {
            String name = futils.shortName(proteinf)
            String newf = "$pointsDir/$name"
            String newfrel = "data/$name"

            log.info "copying [$proteinf] to [$newf]"
            futils.copy(proteinf, newf)

            proteinf = newfrel
        }

        String colorPocketSurfaces = colorPocketSurfaces(pair)

        String colorExposedAtoms = ""
        // XXX
        //colorExposedAtoms = pair.prediction.protein.exposedAtoms.list.collect { "set surface_color, grey30, id $it.PDBserial \n set sphere_color, grey30, id $it.PDBserial" }.join("\n")

//        #set ray_shadow, 0
//        #set depth_cue, 0
//        #set ray_trace_fog, 0
          //#set antialias, 2
//        set bg_rgb_top, [10,10,10]
//        set bg_rgb_bottom, [36,36,85]
        futils.overwrite(pmlf, """
from pymol import cmd,stored

set depth_cue, 1
set fog_start, 0.4
set bg_gradient, 1

set_color b_col, [36,36,85]
set_color t_col, [10,10,10]
set bg_rgb_bottom, b_col
set bg_rgb_top, t_col

set  spec_power  =  200
set  spec_refl   =  0

load $proteinf, protein
#create protein, fprotein and polymer
create ligands, protein and organic
select xlig, protein and organic
delete xlig
#delete fprotein

#color bluewhite, fprotein
#remove solvent
#set stick_color, magenta
#hide lines
#show sticks

hide everything, all

color white, elem c
color bluewhite, protein
#show_as cartoon, protein
show surface, protein
#set transparency, 0.15


#show_as sticks, ligands
show sticks, ligands
#show spheres, ligand
#set sphere_scale, 0.33
set stick_color, magenta


load $pointsfRelName, points
hide nonbonded, points
show nb_spheres, points
cmd.spectrum("b", "green_red", selection="points", minimum=0, maximum=0.7)

#select pockets, resn STP
stored.list=[]
cmd.iterate("(resn STP)","stored.list.append(resi)")    #read info about residues STP
#print stored.list
lastSTP=stored.list[-1] #get the index of the last residu
hide lines, resn STP

#show spheres, resn STP
cmd.select("rest", "resn STP and resi 0")

for my_index in range(1,int(lastSTP)+1): cmd.select("pocket"+str(my_index), "resn STP and resi "+str(my_index))
#for my_index in range(2,int(lastSTP)+2): cmd.color(my_index,"pocket"+str(my_index))
for my_index in range(1,int(lastSTP)+1): cmd.show("spheres","pocket"+str(my_index))
for my_index in range(1,int(lastSTP)+1): cmd.set("sphere_scale","0.4","pocket"+str(my_index))
for my_index in range(1,int(lastSTP)+1): cmd.set("sphere_transparency","0.1","pocket"+str(my_index))

#load $pointsf0RelName, points0
#hide nonbonded, points0
#show nb_spheres, points0
#cmd.spectrum("b", "yellow_blue", selection="points0", minimum=0.3, maximum=1)

$colorExposedAtoms

$colorPocketSurfaces

deselect

orient

#set ray_trace_mode, 1

            """)
         // predefined gradients:  http://kpwu.wordpress.com/2007/11/27/pymol-example-coloring-surface-by-b-factor/
        // http://cupnet.net/pdb_format/
        // http://www.pymolwiki.org/index.php/Colorama

        Writer pdb = futils.overwrite(pointsf)
        int i = 0
        for (LabeledPoint lp : rescorer.labeledPoints) {
            double beta = lp.hist[1]
            Atom p = lp.point
                      //HETATM   73 H    POC 1   0      13.842  20.130  -4.420  0.50  0.50
   //         pdb.printf "HETATM%5d H    POC 1   0    %8.3f%8.3f%8.3f  0.50%6.3f\n", i, p.x, p.y, p.z, beta
            def lab = "STP"
//            if (lp.pocket!=0)
//                lab = "POC"

            pdb.printf "HETATM%5d H    %3s 1  %2d    %8.3f%8.3f%8.3f  0.50%6.3f\n", i, lab, lp.pocket, p.x, p.y, p.z, beta
            i++
        }
        pdb.close()

//        double q = -0.03
//
//        pdb = FileUtils.overwrite(pointsf0)
//        i = 0
//        for (LabeledPoint lp : rescorer.labeledPoints) {
//            double beta = lp.hist[0]
//            Atom p = lp.point
//
//            log.info "POINT:  ${lp.hist[0]}   ${lp.hist[1]}   ${lp.hist[0]+lp.hist[1]}"
//
//            //HETATM   73 H    POC 1   0      13.842  20.130  -4.420  0.50  0.50
//            pdb.printf "HETATM%5d H    POC 1   0    %8.3f%8.3f%8.3f  0.50%6.3f\n", i, p.x+q, p.y+q, p.z+q, beta
//            i++
//        }
//        pdb.close()

    }

}