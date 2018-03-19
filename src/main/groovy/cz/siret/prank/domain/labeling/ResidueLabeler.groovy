package cz.siret.prank.domain.labeling

import cz.siret.prank.domain.Protein
import cz.siret.prank.domain.Residues
import cz.siret.prank.program.PrankException
import groovy.transform.CompileStatic

/**
 * Provides ResidueLabeling for proteins.
 */
@CompileStatic
abstract class ResidueLabeler<L> {

    abstract ResidueLabeling<L> labelResidues(Residues residues, Protein protein)

    abstract boolean isBinary()

    BinaryLabeling getBinaryLabeling(Residues residues, Protein protein) {
        if (isBinary()) {
            (BinaryLabeling) labelResidues(residues, protein)
        } else {
            throw new PrankException("Residue labeler not binary!")
        }
    }

    static ResidueLabeler loadFromFile(String format, String fname) {
        switch (format) {
            case "sprint":
                return SprintLabelingLoader.loadFromFile(fname)
            default:
                throw new PrankException("Invalid labeling file format: " + format)
        }
    }

}
