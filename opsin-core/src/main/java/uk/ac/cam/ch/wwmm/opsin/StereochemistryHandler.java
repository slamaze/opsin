package uk.ac.cam.ch.wwmm.opsin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.Map.Entry;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import uk.ac.cam.ch.wwmm.opsin.BondStereo.BondStereoValue;
import uk.ac.cam.ch.wwmm.opsin.OpsinWarning.OpsinWarningType;
import uk.ac.cam.ch.wwmm.opsin.StereoAnalyser.StereoBond;
import uk.ac.cam.ch.wwmm.opsin.StereoAnalyser.StereoCentre;
import static uk.ac.cam.ch.wwmm.opsin.XmlDeclarations.*;

/**
 * Identifies stereocentres, assigns stereochemistry elements to them and then uses the CIP rules to calculate appropriates atomParity/bondstereo tags
 * @author dl387
 *
 */
class StereochemistryHandler {
	
	private static final Logger LOG = LogManager.getLogger(StereochemistryHandler.class);
	
	private final BuildState state;
	private final Map<Atom, StereoCentre> atomStereoCentreMap;
	private final Map<Bond, StereoBond> bondStereoBondMap;
	private final Map<Atom, StereoCentre> notExplicitlyDefinedStereoCentreMap;
	private final Map<Bond, StereoBond> notExplicitlyDefinedStereoBondMap;
	
	StereochemistryHandler(BuildState state, Map<Atom, StereoCentre> atomStereoCentreMap, Map<Bond, StereoBond> bondStereoBondMap) {
		this.state = state;
		this.atomStereoCentreMap = atomStereoCentreMap;
		notExplicitlyDefinedStereoCentreMap = new HashMap<>(atomStereoCentreMap);
		this.bondStereoBondMap = bondStereoBondMap;
		notExplicitlyDefinedStereoBondMap = new HashMap<>(bondStereoBondMap);
	}

	/**
	 * Processes and assigns stereochemistry elements to appropriate fragments
	 * @param stereoChemistryEls 
	 * @throws StructureBuildingException
	 */
	void applyStereochemicalElements(List<Element> stereoChemistryEls) throws StructureBuildingException {
		List<Element> locantedStereoChemistryEls = new ArrayList<>();
		List<Element> unlocantedStereoChemistryEls = new ArrayList<>();
		List<Element> carbohydrateStereoChemistryEls = new ArrayList<>();
		List<Element> globalRacemicOrRelative = new ArrayList<>();
		for (Element stereoChemistryElement : stereoChemistryEls) {
			if (stereoChemistryElement.getAttributeValue(LOCANT_ATR)!=null){
				locantedStereoChemistryEls.add(stereoChemistryElement);
			}
			else if (stereoChemistryElement.getAttributeValue(TYPE_ATR).equals(CARBOHYDRATECONFIGURATIONPREFIX_TYPE_VAL)){
				carbohydrateStereoChemistryEls.add(stereoChemistryElement);
			}
			else if (stereoChemistryElement.getAttributeValue(TYPE_ATR).equals(RAC_TYPE_VAL) ||
							 stereoChemistryElement.getAttributeValue(TYPE_ATR).equals(REL_TYPE_VAL)) {
				globalRacemicOrRelative.add(stereoChemistryElement);
			} else{
				unlocantedStereoChemistryEls.add(stereoChemistryElement);
			}
		}
		//perform locanted before unlocanted to avoid unlocanted elements using the stereocentres a locanted element refers to
		for (Element stereochemistryEl : locantedStereoChemistryEls) {
			try {
				matchStereochemistryToAtomsAndBonds(stereochemistryEl);
			}
			catch (StereochemistryException e) {
				if (state.n2sConfig.warnRatherThanFailOnUninterpretableStereochemistry()){
					state.addWarning(OpsinWarningType.STEREOCHEMISTRY_IGNORED, e.getMessage());
				}
				else{
					throw e;
				}
			}
		}
		if (!carbohydrateStereoChemistryEls.isEmpty()){
			processCarbohydrateStereochemistry(carbohydrateStereoChemistryEls);
		}
		for (Element stereochemistryEl : unlocantedStereoChemistryEls) {
			try {
				matchStereochemistryToAtomsAndBonds(stereochemistryEl);
			}
			catch (StereochemistryException e) {
				if (state.n2sConfig.warnRatherThanFailOnUninterpretableStereochemistry()){
					state.addWarning(OpsinWarningType.STEREOCHEMISTRY_IGNORED, e.getMessage());
				}
				else{
					throw e;
				}
			}
		}

		if (globalRacemicOrRelative.size() > 1) {
			if (state.n2sConfig.warnRatherThanFailOnUninterpretableStereochemistry()){
				state.addWarning(OpsinWarningType.STEREOCHEMISTRY_IGNORED, "More than one global indicator of rac- or rel- was specified");
			} else {
				throw new StructureBuildingException("More than one global indicator of rac- or rel- was specified");
			}
		}

		for (Element stereochemistryEl : globalRacemicOrRelative) {
			try {
				matchStereochemistryToAtomsAndBonds(stereochemistryEl);
			}
			catch (StereochemistryException e) {
				if (state.n2sConfig.warnRatherThanFailOnUninterpretableStereochemistry()){
					state.addWarning(OpsinWarningType.STEREOCHEMISTRY_IGNORED, e.getMessage());
				}
				else{
					throw e;
				}
			}
		}
	}

	/**
	 * Checks that all atomParity and bondStereo elements correspond to identified stereocentres.
	 * If they do not, they have assumedly been removed by substitution and hence the atomPaity/bondStereo is removed
	 * @param bondsWithPreDefinedBondStereo 
	 * @param atomsWithPreDefinedAtomParity 
	 */
	void removeRedundantStereoCentres(List<Atom> atomsWithPreDefinedAtomParity, List<Bond> bondsWithPreDefinedBondStereo) {
		for (Atom atom : atomsWithPreDefinedAtomParity) {
			if (!atomStereoCentreMap.containsKey(atom)){
				atom.setAtomParity(null);
			}
		}
		for (Bond bond : bondsWithPreDefinedBondStereo) {
			if (!bondStereoBondMap.containsKey(bond)){
				bond.setBondStereo(null);
			}
		}
	}

	/**
	 * Attempts to locate a suitable atom/bond for the stereochemistryEl, applies it and detaches the stereochemsitry
	 * @param stereoChemistryEl
	 * @throws StructureBuildingException
	 * @throws StereochemistryException 
	 */
	private void matchStereochemistryToAtomsAndBonds(Element stereoChemistryEl) throws StructureBuildingException, StereochemistryException {
		String stereoChemistryType =stereoChemistryEl.getAttributeValue(TYPE_ATR);
		if (stereoChemistryType.equals(R_OR_S_TYPE_VAL)){
			assignStereoCentre(stereoChemistryEl);
		}
		else if (stereoChemistryType.equals(E_OR_Z_TYPE_VAL)){
			assignStereoBond(stereoChemistryEl);
		}
		else if (stereoChemistryType.equals(CISORTRANS_TYPE_VAL)){
			if (!assignCisTransOnRing(stereoChemistryEl)){
				assignStereoBond(stereoChemistryEl);
			}
		}
		else if (stereoChemistryType.equals(ALPHA_OR_BETA_TYPE_VAL)){
			assignAlphaBetaXiStereochem(stereoChemistryEl);
		}
		else if (stereoChemistryType.equals(DLSTEREOCHEMISTRY_TYPE_VAL)){
			assignDlStereochem(stereoChemistryEl);
		}
		else if (stereoChemistryType.equals(RAC_TYPE_VAL)){
			applyGlobalRacOrRelFlags(stereoChemistryEl, StereoGroup.Rac);
		}
		else if (stereoChemistryType.equals(REL_TYPE_VAL)){
			applyGlobalRacOrRelFlags(stereoChemistryEl, StereoGroup.Rel);
		}
		else if (stereoChemistryType.equals(ENDO_EXO_SYN_ANTI_TYPE_VAL)){
			throw new StereochemistryException(stereoChemistryType + " stereochemistry is not currently interpretable by OPSIN");
		}
		else if (stereoChemistryType.equals(RELATIVECISTRANS_TYPE_VAL)){
			throw new StereochemistryException(stereoChemistryType + " stereochemistry is not currently interpretable by OPSIN");
		}
		else if (stereoChemistryType.equals(AXIAL_TYPE_VAL)){
			throw new StereochemistryException(stereoChemistryType + " stereochemistry is not currently interpretable by OPSIN");
		}
		else if (stereoChemistryType.equals(OPTICALROTATION_TYPE_VAL)){
			state.addWarning(OpsinWarningType.STEREOCHEMISTRY_IGNORED, "Optical rotation cannot be algorithmically used to assign stereochemistry. This term was ignored: " + stereoChemistryEl.getValue());
		}
		else{
			throw new StructureBuildingException("Unexpected stereochemistry type: " +stereoChemistryType);
		}
		stereoChemistryEl.detach();
	}

	/**
	 * Groups carbohydrateStereoChemistryEls by their parent element and
	 * sends them for further processing
	 * @param carbohydrateStereoChemistryEls
	 * @throws StructureBuildingException 
	 */
	private void processCarbohydrateStereochemistry(List<Element> carbohydrateStereoChemistryEls) throws StructureBuildingException {
		Map<Element, List<Element>> groupToStereochemEls = new HashMap<>();
		for (Element carbohydrateStereoChemistryEl : carbohydrateStereoChemistryEls) {
			Element nextGroup = OpsinTools.getNextSibling(carbohydrateStereoChemistryEl, GROUP_EL);
			if (nextGroup ==null || (!SYSTEMATICCARBOHYDRATESTEMALDOSE_SUBTYPE_VAL.equals(nextGroup.getAttributeValue(SUBTYPE_ATR)) &&
					!SYSTEMATICCARBOHYDRATESTEMKETOSE_SUBTYPE_VAL.equals(nextGroup.getAttributeValue(SUBTYPE_ATR)))){
				throw new RuntimeException("OPSIN bug: Could not find carbohydrate chain stem to apply stereochemistry to");
			}
			if (groupToStereochemEls.get(nextGroup)==null){
				groupToStereochemEls.put(nextGroup, new ArrayList<>());
			}
			groupToStereochemEls.get(nextGroup).add(carbohydrateStereoChemistryEl);
		}
		for (Entry<Element, List<Element>> entry : groupToStereochemEls.entrySet()) {
			assignCarbohydratePrefixStereochem(entry.getKey(), entry.getValue());
		}
	}

	/**
	 * Applies global racemic/relative stereochemistry. The logic is follows:
	 * - Atoms are divided into two sets defined/undefined.
	 * - (1) If there is a single undefined stereogenic atom it is set to R and marked as racemic/relative
	 * - (2) If there is more than one the global specification is ignored.
	 * - (3) If there are no undefined stereocenters then they are set to racemic/relative
	 *
	 * @param stereoChemistryEl the stereo chemistry element
	 * @param group the stereo group Rac(emic) or Rel(ative).
	 * @throws StructureBuildingException
	 * @throws StereochemistryException
	 */
	private void applyGlobalRacOrRelFlags(Element stereoChemistryEl, StereoGroup group) throws StructureBuildingException, StereochemistryException {

		Element wordParent = stereoChemistryEl.getParent();
		while (wordParent != null &&
				!wordParent.getName().equals(WORD_EL)) {
			wordParent = wordParent.getParent();
		}
		if (wordParent == null)
			return;

		Element parentSubBracketOrRoot = stereoChemistryEl.getParent();
		List<Fragment> possibleFragments = new ArrayList<>(StructureBuildingMethods.findAlternativeFragments(parentSubBracketOrRoot));
		List<Element> adjacentGroupEls = OpsinTools.getDescendantElementsWithTagName(parentSubBracketOrRoot, GROUP_EL);
		for (int i = adjacentGroupEls.size()-1; i >=0; i--)
			possibleFragments.add(adjacentGroupEls.get(i).getFrag());

		// collect all fragments that occur after
		List<Element> words = OpsinTools.getNextSiblingsOfType(wordParent, WORD_EL);
		for (Element word : words) {
			List<Element> possibleGroups = OpsinTools.getDescendantElementsWithTagName(word, GROUP_EL);
			for (int i = possibleGroups.size() - 1; i >= 0; i--) {
				possibleFragments.add(possibleGroups.get(i).getFrag());
			}
		}

		// first for all-ready (un)/defined stereochemistry
		List<Atom> undefinedStereo = new ArrayList<>();
		List<Atom> definedStereo   = new ArrayList<>();
		for (Fragment fragment : possibleFragments) {
			List<Atom> atomList = fragment.getAtomList();
			for (Atom potentialStereoAtom : atomList) {
				if (potentialStereoAtom.getAtomParity() != null)
					definedStereo.add(potentialStereoAtom);
				else if (notExplicitlyDefinedStereoCentreMap.get(potentialStereoAtom) != null)
					undefinedStereo.add(potentialStereoAtom);
			}
		}

		if (undefinedStereo.size() > 0) {

			if (undefinedStereo.size() > 1) {
				state.addWarning(OpsinWarningType.STEREOCHEMISTRY_IGNORED,
						"More than one undefined stereocenter for rac- or rel- mixture");
				return;
			}

			try {
				applyStereoChemistryToStereoCentre(undefinedStereo.get(0),
						notExplicitlyDefinedStereoCentreMap.get(undefinedStereo.get(0)),
						"R");
			} catch (CipOrderingException e) {
				state.addWarning(OpsinWarningType.STEREOCHEMISTRY_IGNORED,
						         "Could not set rac- or rel- stereochemistry: " + e.getMessage());
				return;
			}
			undefinedStereo.get(0).setStereoGroup(group);
			notExplicitlyDefinedStereoCentreMap.remove(undefinedStereo.get(0));
		} else {
			for (Atom atom : definedStereo) {
				atom.setStereoGroup(group);
			}
		}
	}

	/**
	 * Handles R/S stereochemistry. r/s is not currently handled
	 * @param stereoChemistryEl
	 * @throws StructureBuildingException
	 * @throws StereochemistryException 
	 */
	private void assignStereoCentre(Element stereoChemistryEl) throws StructureBuildingException, StereochemistryException {
		//generally the LAST group in this list will be the appropriate group e.g. (5S)-5-ethyl-6-methylheptane where the heptane is the appropriate group
		//we use the same algorithm as for unlocanted substitution so as to deprecate assignment into brackets
		Element parentSubBracketOrRoot = stereoChemistryEl.getParent();
		List<Fragment> possibleFragments = StructureBuildingMethods.findAlternativeFragments(parentSubBracketOrRoot);
		List<Element> adjacentGroupEls = OpsinTools.getDescendantElementsWithTagName(parentSubBracketOrRoot, GROUP_EL);
		for (int i = adjacentGroupEls.size()-1; i >=0; i--) {
			possibleFragments.add(adjacentGroupEls.get(i).getFrag());
		}
		String locant = stereoChemistryEl.getAttributeValue(LOCANT_ATR);
		String rOrS = stereoChemistryEl.getAttributeValue(VALUE_ATR);
		String grpStr = stereoChemistryEl.getAttributeValue(STEREOGROUP_ATR);
		StereoGroup grp = grpStr != null ? StereoGroup.valueOf(grpStr) : StereoGroup.Unk;
		for (Fragment fragment : possibleFragments) {
			if (attemptAssignmentOfStereoCentreToFragment(fragment, rOrS, locant, grp)) {
				return;
			}
		}
		Element possibleWordParent = parentSubBracketOrRoot.getParent();
		if (possibleWordParent.getName().equals(WORD_EL) && possibleWordParent.getChild(0).equals(parentSubBracketOrRoot)){
			//something like (3R,4R,5R)-ethyl 4-acetamido-5-amino-3-(pentan-3-yloxy)cyclohex-1-enecarboxylate
			//i.e. the stereochemistry is in a different word to what it is applied to
			List<Element> words = OpsinTools.getNextSiblingsOfType(possibleWordParent, WORD_EL);
			for (Element word : words) {
				List<Element> possibleGroups = OpsinTools.getDescendantElementsWithTagName(word, GROUP_EL);
				for (int i = possibleGroups.size()-1; i >=0; i--) {
					if (attemptAssignmentOfStereoCentreToFragment(possibleGroups.get(i).getFrag(), rOrS, locant, grp)) {
						return;
					}
				}
			}
		}
		throw new StereochemistryException("Could not find atom that: " + stereoChemistryEl.toXML() + " appeared to be referring to");
	}


	private boolean attemptAssignmentOfStereoCentreToFragment(Fragment fragment, String rOrS, String locant, StereoGroup grp) throws StereochemistryException, StructureBuildingException {
		if (locant == null) {//undefined locant
			List<Atom> atomList = fragment.getAtomList();
			for (Atom potentialStereoAtom : atomList) {
				if (notExplicitlyDefinedStereoCentreMap.containsKey(potentialStereoAtom)){
					applyStereoChemistryToStereoCentre(potentialStereoAtom, notExplicitlyDefinedStereoCentreMap.get(potentialStereoAtom), rOrS);
					potentialStereoAtom.setStereoGroup(grp);
					notExplicitlyDefinedStereoCentreMap.remove(potentialStereoAtom);
					return true;
				}
			}
		}
		else{
			Atom potentialStereoAtom = fragment.getAtomByLocant(locant);
			if (potentialStereoAtom !=null && notExplicitlyDefinedStereoCentreMap.containsKey(potentialStereoAtom)){
				applyStereoChemistryToStereoCentre(potentialStereoAtom, notExplicitlyDefinedStereoCentreMap.get(potentialStereoAtom), rOrS);
				potentialStereoAtom.setStereoGroup(grp);
				notExplicitlyDefinedStereoCentreMap.remove(potentialStereoAtom);
				return true;
			}
		}
		return false;
	}

	/**
	 * Assigns atom parity to the given atom in accordance with the CIP rules
	 * @param atom The stereoAtom
	 * @param stereoCentre
	 * @param rOrS The description given in the name
	 * @throws StructureBuildingException
	 * @throws StereochemistryException
	 */
	private void applyStereoChemistryToStereoCentre(Atom atom, StereoCentre stereoCentre, String rOrS) throws StructureBuildingException, StereochemistryException {
		List<Atom> cipOrderedAtoms =stereoCentre.getCipOrderedAtoms();
		if (cipOrderedAtoms.size()!=4){
			throw new StructureBuildingException("Only tetrahedral chirality is currently supported");
		}
		Atom[] atomRefs4 = new Atom[4];
		atomRefs4[0] = cipOrderedAtoms.get(cipOrderedAtoms.size()-1);
		for (int i = 0; i < cipOrderedAtoms.size() -1; i++) {//from highest to lowest (true for S) hence atomParity 1 for S
			atomRefs4[i+1] = cipOrderedAtoms.get(i);
		}
		if (rOrS.equals("R")) {
			atom.setAtomParity(atomRefs4, -1);
		}
		else if (rOrS.equals("S")) {
			atom.setAtomParity(atomRefs4, 1);
		}
		else{
			throw new StructureBuildingException("Unexpected stereochemistry type: " + rOrS);
		}
	}


	/**
	 * Handles E/Z stereochemistry and cis/trans in cases where cis/trans unambiguously corresponds to E/Z
	 * @param stereoChemistryEl
	 * @throws StructureBuildingException
	 * @throws StereochemistryException 
	 */
	private void assignStereoBond(Element stereoChemistryEl) throws StructureBuildingException, StereochemistryException {
		//generally the LAST group in this list will be the appropriate groups e.g. (2Z)-5-ethyl-6-methylhex-2-ene where the hex-2-ene is the appropriate group
		//we use the same algorithm as for unlocanted substitution so as to deprecate assignment into brackets
		Element parentSubBracketOrRoot = stereoChemistryEl.getParent();
		List<Fragment> possibleFragments = StructureBuildingMethods.findAlternativeFragments(parentSubBracketOrRoot);
		List<Element> adjacentGroupEls = OpsinTools.getDescendantElementsWithTagName(parentSubBracketOrRoot, GROUP_EL);
		for (int i = adjacentGroupEls.size()-1; i >=0; i--) {
			possibleFragments.add(adjacentGroupEls.get(i).getFrag());
		}
		String locant = stereoChemistryEl.getAttributeValue(LOCANT_ATR);
		String eOrZ = stereoChemistryEl.getAttributeValue(VALUE_ATR);
		boolean isCisTrans =false;
		if (stereoChemistryEl.getAttributeValue(TYPE_ATR).equals(CISORTRANS_TYPE_VAL)){
			isCisTrans =true;
			String cisOrTrans = stereoChemistryEl.getAttributeValue(VALUE_ATR);
			if (cisOrTrans.equalsIgnoreCase("cis")){
				eOrZ = "Z";
			}
			else if (cisOrTrans.equalsIgnoreCase("trans")){
				eOrZ = "E";
			}
			else{
				throw new StructureBuildingException("Unexpected cis/trans stereochemistry type: " +cisOrTrans);
			}
		}
		for (Fragment fragment : possibleFragments) {
			if (attemptAssignmentOfStereoBondToFragment(fragment, eOrZ, locant, isCisTrans)) {
				return;
			}
		}
		Element possibleWordParent = parentSubBracketOrRoot.getParent();
		if (possibleWordParent.getName().equals(WORD_EL) && possibleWordParent.getAttributeValue(TYPE_ATR).equals(WordType.substituent.toString())){
			//the element is in front of a substituent and may refer to the full group
			//i.e. the stereochemistry is in a different word to what it is applied to
			List<Element> words = OpsinTools.getChildElementsWithTagNameAndAttribute(possibleWordParent.getParent(), WORD_EL, TYPE_ATR, WordType.full.toString());
			for (Element word : words) {
				List<Element> possibleGroups = OpsinTools.getDescendantElementsWithTagName(word, GROUP_EL);
				for (int i = possibleGroups.size()-1; i >=0; i--) {
					if (attemptAssignmentOfStereoBondToFragment(possibleGroups.get(i).getFrag(), eOrZ, locant, isCisTrans)) {
						return;
					}
				}
			}
		}
		if (isCisTrans){
			throw new StereochemistryException("Could not find bond that: " + stereoChemistryEl.toXML() + " could refer unambiguously to");
		}
		else{
			throw new StereochemistryException("Could not find bond that: " + stereoChemistryEl.toXML() + " was referring to");
		}
	}


	private boolean attemptAssignmentOfStereoBondToFragment(Fragment fragment, String eOrZ, String locant, boolean isCisTrans) throws StereochemistryException {
		if (locant == null){//undefined locant
			Set<Bond> bondSet = fragment.getBondSet();
			for (Bond potentialBond : bondSet) {
				if (notExplicitlyDefinedStereoBondMap.containsKey(potentialBond) && (!isCisTrans || cisTransUnambiguousOnBond(potentialBond))){
					applyStereoChemistryToStereoBond(potentialBond, notExplicitlyDefinedStereoBondMap.get(potentialBond), eOrZ);
					notExplicitlyDefinedStereoBondMap.remove(potentialBond);
					return true;
				}
			}
			List<Bond> sortedInterFragmentBonds = sortInterFragmentBonds(state.fragManager.getInterFragmentBonds(fragment), fragment);
			for (Bond potentialBond : sortedInterFragmentBonds) {
				if (notExplicitlyDefinedStereoBondMap.containsKey(potentialBond) && (!isCisTrans || cisTransUnambiguousOnBond(potentialBond))){
					applyStereoChemistryToStereoBond(potentialBond, notExplicitlyDefinedStereoBondMap.get(potentialBond), eOrZ);
					notExplicitlyDefinedStereoBondMap.remove(potentialBond);
					return true;
				}
			}
		}
		else{
			Atom firstAtomInBond = fragment.getAtomByLocant(locant);
			if (firstAtomInBond !=null){
				List<Bond> bonds = firstAtomInBond.getBonds();
				for (Bond potentialBond : bonds) {
					if (notExplicitlyDefinedStereoBondMap.containsKey(potentialBond) && (!isCisTrans || cisTransUnambiguousOnBond(potentialBond))){
						applyStereoChemistryToStereoBond(potentialBond, notExplicitlyDefinedStereoBondMap.get(potentialBond), eOrZ);
						notExplicitlyDefinedStereoBondMap.remove(potentialBond);
						return true;
					}
				}
			}
		}
		return false;
	}

	/**
	 * Does the stereoBond have a hydrogen connected to both ends of it.
	 * If not it is ambiguous when used in conjunction with cis/trans and E/Z should be used.
	 * @param potentialBond
	 * @return
	 */
	static boolean cisTransUnambiguousOnBond(Bond potentialBond) {
		List<Atom> neighbours1 = potentialBond.getFromAtom().getAtomNeighbours();
		boolean foundHydrogen1 =false;
		for (Atom neighbour : neighbours1) {
			if (neighbour.getElement() == ChemEl.H){
				foundHydrogen1 =true;
			}
		}
		
		List<Atom> neighbours2 = potentialBond.getToAtom().getAtomNeighbours();
		boolean foundHydrogen2 =false;
		for (Atom neighbour : neighbours2) {
			if (neighbour.getElement() == ChemEl.H){
				foundHydrogen2 =true;
			}
		}
		return (foundHydrogen1 && foundHydrogen2);
	}


	/**
	 * Sorts bonds such that those originating from the given fragment are preferred
	 * @param interFragmentBonds A set of interFragment Bonds
	 * @param preferredOriginatingFragment 
	 * @return A sorted list
	 */
	private List<Bond> sortInterFragmentBonds(Set<Bond> interFragmentBonds, Fragment preferredOriginatingFragment) {
		List<Bond> interFragmentBondList = new ArrayList<>();
		for (Bond bond : interFragmentBonds) {
			if (bond.getFromAtom().getFrag() ==preferredOriginatingFragment){
				interFragmentBondList.add(0, bond);
			}
			else{
				interFragmentBondList.add(bond);
			}
		}
		return interFragmentBondList;
	}


	/**
	 * Assigns bondstereo to the given bond in accordance with the CIP rules
	 * @param bond The stereobond
	 * @param stereoBond
	 * @param eOrZ The stereo description given in the name
	 * @throws StereochemistryException 
	 */
	private void applyStereoChemistryToStereoBond(Bond bond, StereoBond stereoBond, String eOrZ ) throws StereochemistryException {
		List<Atom> stereoBondAtoms = stereoBond.getOrderedStereoAtoms();
		//stereoBondAtoms contains the higher priority atom at one end, the two bond atoms and the higher priority atom at the other end
		Atom[] atomRefs4 = new Atom[4];
		atomRefs4[0] = stereoBondAtoms.get(0);
		atomRefs4[1] = stereoBondAtoms.get(1);
		atomRefs4[2] = stereoBondAtoms.get(2);
		atomRefs4[3] = stereoBondAtoms.get(3);
		if (eOrZ.equals("E")){
			bond.setBondStereoElement(atomRefs4, BondStereoValue.TRANS);
		}
		else if (eOrZ.equals("Z")){
			bond.setBondStereoElement(atomRefs4, BondStereoValue.CIS);
		}
		else if (eOrZ.equals("EZ")){
			bond.setBondStereo(null);
		}
		else{
			throw new IllegalArgumentException("Unexpected stereochemistry type: " + eOrZ);
		}
	}
	
	/**
	 * Searches for instances of two tetrahedral stereocentres/psuedo-stereocentres
	 * then sets their configuration such that the substituents at these centres are cis or trans to each other
	 * @param stereoChemistryEl
	 * @return
	 * @throws StructureBuildingException
	 */
	private boolean assignCisTransOnRing(Element stereoChemistryEl) throws StructureBuildingException {
		if (stereoChemistryEl.getAttribute(LOCANT_ATR) != null) {
			return false;
		}
		Element parentSubBracketOrRoot = stereoChemistryEl.getParent();
		List<Fragment> possibleFragments = StructureBuildingMethods.findAlternativeFragments(parentSubBracketOrRoot);
		List<Element> adjacentGroupEls = OpsinTools.getDescendantElementsWithTagName(parentSubBracketOrRoot, GROUP_EL);
		for (int i = adjacentGroupEls.size()-1; i >=0; i--) {
			possibleFragments.add(adjacentGroupEls.get(i).getFrag());
		}
		for (Fragment fragment : possibleFragments) {
			if (attemptAssignmentOfCisTransRingStereoToFragment(fragment, stereoChemistryEl)){
				return true;
			}
		}

		Element possibleWordParent = parentSubBracketOrRoot.getParent();
		if (possibleWordParent.getName().equals(WORD_EL) && possibleWordParent.getChild(0).equals(parentSubBracketOrRoot)){
			//stereochemistry is in a different word to what it is applied to
			List<Element> words = OpsinTools.getNextSiblingsOfType(possibleWordParent, WORD_EL);
			for (Element word : words) {
				List<Element> possibleGroups = OpsinTools.getDescendantElementsWithTagName(word, GROUP_EL);
				for (int i = possibleGroups.size()-1; i >=0; i--) {
					if (attemptAssignmentOfCisTransRingStereoToFragment(possibleGroups.get(i).getFrag(), stereoChemistryEl)) {
						return true;
					}
				}
			}
		}
		return false;
	}


	private boolean attemptAssignmentOfCisTransRingStereoToFragment(Fragment fragment, Element stereoChemistryEl) throws StructureBuildingException {
		List<Atom> atomList = fragment.getAtomList();
		List<Atom> chosenStereoAtoms = new ArrayList<>();
		List<Atom> stereoAtomsWithTwoNonHydrogen = new ArrayList<>();
		for (Atom potentialStereoAtom : atomList) {
			if (potentialStereoAtom.getAtomIsInACycle()){
				List<Atom> neighbours = potentialStereoAtom.getAtomNeighbours();
				if (neighbours.size() == 4) {
					int hydrogenCount = 0;
					int acylicOrNotInFrag = 0;
					for (Atom neighbour : neighbours) {
						if (neighbour.getElement() == ChemEl.H) {
							hydrogenCount++;
						}
						if (!neighbour.getAtomIsInACycle() || !atomList.contains(neighbour)) {
							acylicOrNotInFrag++;
						}
					}
					if (hydrogenCount == 1 || (hydrogenCount == 0 && acylicOrNotInFrag == 1)) {
						chosenStereoAtoms.add(potentialStereoAtom);
					}
					else if (hydrogenCount == 0 && acylicOrNotInFrag == 2 && notExplicitlyDefinedStereoCentreMap.containsKey(potentialStereoAtom)) {
						stereoAtomsWithTwoNonHydrogen.add(potentialStereoAtom);
					}
				}
			}
		}
		boolean chooseAtomByCip = false;
		if (chosenStereoAtoms.size() < 2 && chosenStereoAtoms.size() + stereoAtomsWithTwoNonHydrogen.size() == 2) {
			chosenStereoAtoms.addAll(stereoAtomsWithTwoNonHydrogen);
			chooseAtomByCip = true;
		}
		if (chosenStereoAtoms.size() == 2) {
			Atom a1 = chosenStereoAtoms.get(0);
			Atom a2 = chosenStereoAtoms.get(1);

			if (a1.getAtomParity() != null && a2.getAtomParity() != null){//one can have defined stereochemistry but not both
				return false;
			}

			Set<Bond> peripheryBonds = determinePeripheryBonds(fragment);
			List<List<Atom>> paths = CycleDetector.getPathBetweenAtomsUsingBonds(a1, a2, peripheryBonds);
			if (paths.size() != 2) {
				return false;
			}
			applyStereoChemistryToCisTransOnRing(a1, a2, paths, atomList, stereoChemistryEl.getAttributeValue(VALUE_ATR), chooseAtomByCip);
			notExplicitlyDefinedStereoCentreMap.remove(chosenStereoAtoms.get(0));
			notExplicitlyDefinedStereoCentreMap.remove(chosenStereoAtoms.get(1));
			if (chooseAtomByCip) {
				state.addIsAmbiguous("Ring cis/trans applied to stereocenter where no hydrogen was present. Cahn-Ingold-Prelog rules used to determine which substituents are cis/trans, but other conventions may be in use");
			}
			return true;
		}
		return false;
	}

	private Set<Bond> determinePeripheryBonds(Fragment fragment) {
		List<Ring> rings = SSSRFinder.getSetOfSmallestRings(fragment);
		FusedRingNumberer.setupAdjacentFusedRingProperties(rings);
		Set<Bond> bondsToConsider = new HashSet<>();
		for (Ring ring : rings) {
			for (Bond bond : ring.getBondList()) {
				bondsToConsider.add(bond);
			}
		}
		for (Ring ring : rings) {
			bondsToConsider.removeAll(ring.getFusedBonds());
		}
		return bondsToConsider;
	}

	private void applyStereoChemistryToCisTransOnRing(Atom a1, Atom a2, List<List<Atom>> paths, List<Atom> fragmentAtoms, String cisOrTrans, boolean chooseAtomByCip) throws StructureBuildingException {
		List<Atom> a1Neighbours = a1.getAtomNeighbours();
		Atom[] atomRefs4a1 = new Atom[4];
		Atom firstPathAtom = paths.get(0).size()>0 ? paths.get(0).get(0) : a2;
		atomRefs4a1[2] = firstPathAtom;
		Atom secondPathAtom = paths.get(1).size()>0 ? paths.get(1).get(0) : a2;
		atomRefs4a1[3] = secondPathAtom;
		a1Neighbours.remove(firstPathAtom);
		a1Neighbours.remove(secondPathAtom);
		if (firstPathAtom.equals(secondPathAtom)){
			throw new StructureBuildingException("OPSIN Bug: cannot assign cis/trans on ring stereochemistry");
		}
		if (chooseAtomByCip) {
			atomRefs4a1[1] = getLowestCip(a1, a1Neighbours);
		}
		else {
			atomRefs4a1[1] = getHydrogenOrAcyclicOrOutsideOfFragment(a1Neighbours, fragmentAtoms);
		}

		if (atomRefs4a1[1] ==null){
			throw new StructureBuildingException("OPSIN Bug: cannot assign cis/trans on ring stereochemistry");
		}
		a1Neighbours.remove(atomRefs4a1[1]);
		atomRefs4a1[0] = a1Neighbours.get(0);
		
		
		List<Atom> a2Neighbours = a2.getAtomNeighbours();
		Atom[] atomRefs4a2 = new Atom[4];
		firstPathAtom = paths.get(0).size()>0 ? paths.get(0).get(paths.get(0).size()-1) : a1;
		atomRefs4a2[2] = firstPathAtom;
		secondPathAtom = paths.get(1).size()>0 ? paths.get(1).get(paths.get(1).size()-1) : a1;
		atomRefs4a2[3] = secondPathAtom;
		a2Neighbours.remove(firstPathAtom);
		a2Neighbours.remove(secondPathAtom);
		if (firstPathAtom.equals(secondPathAtom)){
			throw new StructureBuildingException("OPSIN Bug: cannot assign cis/trans on ring stereochemistry");
		}
		if (chooseAtomByCip) {
			atomRefs4a2[1] = getLowestCip(a2, a2Neighbours);
		}
		else {
			atomRefs4a2[1] = getHydrogenOrAcyclicOrOutsideOfFragment(a2Neighbours, fragmentAtoms);
		}

		if (atomRefs4a2[1] ==null){
			throw new StructureBuildingException("OPSIN Bug: cannot assign cis/trans on ring stereochemistry");
		}
		a2Neighbours.remove(atomRefs4a2[1]);
		atomRefs4a2[0] = a2Neighbours.get(0);
		boolean enantiomer =false;
		if (a1.getAtomParity()!=null){
			if (!checkEquivalencyOfAtomsRefs4AndParity(atomRefs4a1, 1, a1.getAtomParity().getAtomRefs4(), a1.getAtomParity().getParity())){
				enantiomer=true;
			}
		}
		else if (a2.getAtomParity()!=null){
			if (cisOrTrans.equals("cis")){
				if (!checkEquivalencyOfAtomsRefs4AndParity(atomRefs4a2, -1, a2.getAtomParity().getAtomRefs4(), a2.getAtomParity().getParity())){
					enantiomer=true;
				}
			}
			else if (cisOrTrans.equals("trans")){
				if (!checkEquivalencyOfAtomsRefs4AndParity(atomRefs4a2, 1, a2.getAtomParity().getAtomRefs4(), a2.getAtomParity().getParity())){
					enantiomer=true;
				}
			}
		}
		if (enantiomer){
			if (cisOrTrans.equals("cis")){
				a1.setAtomParity(atomRefs4a1, -1);
				a2.setAtomParity(atomRefs4a2, 1);
			}
			else if (cisOrTrans.equals("trans")){
				a1.setAtomParity(atomRefs4a1, -1);
				a2.setAtomParity(atomRefs4a2, -1);
			}
		}
		else{
			if (cisOrTrans.equals("cis")){
				a1.setAtomParity(atomRefs4a1, 1);
				a2.setAtomParity(atomRefs4a2, -1);
			}
			else if (cisOrTrans.equals("trans")){
				a1.setAtomParity(atomRefs4a1, 1);
				a2.setAtomParity(atomRefs4a2, 1);
			}
		}
	}
	
	private Atom getLowestCip(Atom a, List<Atom> atomsToConsider) {
		try {
			List<Atom> neigh = new CipSequenceRules(a).getNeighbouringAtomsInCipOrder();
			for (Atom atom : neigh) {
				if (!atomsToConsider.contains(atom)) {
					continue;
				}
				return atom;
			}
		} catch (CipOrderingException e) {
			LOG.debug(e.getMessage(), e);
		}
		return null;
	}

	private Atom getHydrogenOrAcyclicOrOutsideOfFragment(List<Atom> atoms, List<Atom> fragmentAtoms) {
		for (Atom atom : atoms) {
			if (atom.getElement() == ChemEl.H){
				return atom;
			}
		}
		for (Atom atom : atoms) {
			if (!atom.getAtomIsInACycle() || !fragmentAtoms.contains(atom)){
				return atom;
			}
		}		
		return null;
	}

	/**
	 * Handles assignment of alpha and beta stereochemistry to appropriate ring systems
	 * Currently these are only assignable to natural products
	 * Xi (unknown) stereochemistry is applicable to any tetrahedral centre
	 * @param stereoChemistryEl
	 * @throws StructureBuildingException
	 */
	private void assignAlphaBetaXiStereochem(Element stereoChemistryEl) throws StructureBuildingException {
		Element parentSubBracketOrRoot = stereoChemistryEl.getParent();
		List<Fragment> possibleFragments = StructureBuildingMethods.findAlternativeFragments(parentSubBracketOrRoot);
		Fragment substituentGroup =null;
		if (parentSubBracketOrRoot.getName().equals(SUBSTITUENT_EL)){
			substituentGroup = parentSubBracketOrRoot.getFirstChildElement(GROUP_EL).getFrag();
		}
		List<Element> adjacentGroupEls = OpsinTools.getDescendantElementsWithTagName(parentSubBracketOrRoot, GROUP_EL);
		for (int i = adjacentGroupEls.size()-1; i >=0; i--) {
			possibleFragments.add(adjacentGroupEls.get(i).getFrag());
		}
		String locant = stereoChemistryEl.getAttributeValue(LOCANT_ATR);
		String alphaOrBeta = stereoChemistryEl.getAttributeValue(VALUE_ATR);
		for (Fragment fragment : possibleFragments) {
			Atom potentialStereoAtom = fragment.getAtomByLocant(locant);
			if (potentialStereoAtom !=null && atomStereoCentreMap.containsKey(potentialStereoAtom)){//same stereocentre can be defined twice e.g. one subsituent alpha the other beta
				if (alphaOrBeta.equals("xi")){
					potentialStereoAtom.setAtomParity(null);
				}
				else {
					String alphaBetaClockWiseAtomOrdering = fragment.getTokenEl().getAttributeValue(ALPHABETACLOCKWISEATOMORDERING_ATR);
					if (alphaBetaClockWiseAtomOrdering==null){
						throw new StructureBuildingException("Identified fragment is not known to be able to support alpha/beta stereochemistry");
					}
					applyAlphaBetaStereochemistryToStereoCentre(potentialStereoAtom, fragment, alphaBetaClockWiseAtomOrdering, alphaOrBeta, substituentGroup);
				}
				notExplicitlyDefinedStereoCentreMap.remove(potentialStereoAtom);
				return;
			}
		}
		throw new StructureBuildingException("Could not find atom that: " + stereoChemistryEl.toXML() + " appeared to be referring to");
	}


	/**
	 * Converts the alpha/beta descriptor into an atomRefs4 and parity.
	 * The ordering of atoms in the atomsRefs4 is determined by using the two adjacent atoms along the rings edge as defined by ALPHABETACLOCKWISEATOMORDERING_ATR.
	 * by what atom is also part of the ring or is a hydrogen
	 * and by the substituent atom (as determined by the optional substituentGroup group parameter or by being a non-hydrogen)
	 * @param stereoAtom
	 * @param fragment
	 * @param alphaBetaClockWiseAtomOrdering
	 * @param alphaOrBeta
	 * @param substituentGroup 
	 * @throws StructureBuildingException
	 */
	private void applyAlphaBetaStereochemistryToStereoCentre(Atom stereoAtom, Fragment fragment, String alphaBetaClockWiseAtomOrdering, String alphaOrBeta, Fragment substituentGroup) throws StructureBuildingException {
		List<String> ringOrder = StringTools.arrayToList(alphaBetaClockWiseAtomOrdering.split("/"));
		int positionInList = ringOrder.indexOf(stereoAtom.getFirstLocant());
		if (stereoAtom.getAtomIsInACycle() && positionInList!=-1){
			Atom[] atomRefs4 = new Atom[4];
			List<Atom> neighbours = stereoAtom.getAtomNeighbours();
			if (neighbours.size()==4){
				int previousIndice = positionInList==0 ? ringOrder.size()-1: positionInList -1;
				int nextindice = positionInList==ringOrder.size()-1? 0: positionInList +1;
				atomRefs4[0] = fragment.getAtomByLocantOrThrow(ringOrder.get(previousIndice));
				atomRefs4[3] = fragment.getAtomByLocantOrThrow(ringOrder.get(nextindice));
				neighbours.remove(atomRefs4[0]);
				neighbours.remove(atomRefs4[3]);
				Atom a1 =neighbours.get(0);
				Atom a2 =neighbours.get(1);
				if ((fragment.getAtomList().contains(a1) && ringOrder.contains(a1.getFirstLocant()))){
					atomRefs4[1]=a1;
					atomRefs4[2]=a2;
				}
				else if ((fragment.getAtomList().contains(a2) && ringOrder.contains(a2.getFirstLocant()))){
					atomRefs4[1]=a2;
					atomRefs4[2]=a1;
				}
				else if (a1.getElement() == ChemEl.H && a2.getElement() != ChemEl.H){
					atomRefs4[1]=a2;
					atomRefs4[2]=a1;
				}
				else if (a2.getElement() == ChemEl.H && a1.getElement() != ChemEl.H){
					atomRefs4[1]=a1;
					atomRefs4[2]=a2;
				}//TODO support case where alpha/beta are applied prior to a suffix (and the stereocentre doesn't have a hydrogen) e.g. 17alpha-yl
				else if (substituentGroup !=null && fragment !=substituentGroup && substituentGroup.getAtomList().contains(a1)){
					atomRefs4[1]=a1;
					atomRefs4[2]=a2;
				}
				else if (substituentGroup !=null && fragment !=substituentGroup && substituentGroup.getAtomList().contains(a2)){
					atomRefs4[1]=a2;
					atomRefs4[2]=a1;
				}
				else{
					throw new StructureBuildingException("alpha/beta stereochemistry could not be determined at position " +stereoAtom.getFirstLocant());
				}
				AtomParity previousAtomParity = stereoAtom.getAtomParity();
				if (alphaOrBeta.equals("alpha")){
					stereoAtom.setAtomParity(atomRefs4, 1);
				}
				else if (alphaOrBeta.equals("beta")){
					stereoAtom.setAtomParity(atomRefs4, -1);
				}
				else{
					throw new StructureBuildingException("OPSIN Bug: malformed alpha/beta stereochemistry value");
				}
				if (!notExplicitlyDefinedStereoCentreMap.containsKey(stereoAtom)){//stereocentre has already been defined, need to check for contradiction!
					AtomParity newAtomParity =stereoAtom.getAtomParity();
					if (previousAtomParity == null){
						if (newAtomParity != null){
							throw new StructureBuildingException("contradictory alpha/beta stereochemistry at position " +stereoAtom.getFirstLocant());
						}
					}
					else if (newAtomParity == null){
						if (previousAtomParity != null){
							throw new StructureBuildingException("contradictory alpha/beta stereochemistry at position " +stereoAtom.getFirstLocant());
						}
					}
					else if (!checkEquivalencyOfAtomsRefs4AndParity(previousAtomParity.getAtomRefs4(), previousAtomParity.getParity(), newAtomParity.getAtomRefs4(), newAtomParity.getParity())){
						throw new StructureBuildingException("contradictory alpha/beta stereochemistry at position " +stereoAtom.getFirstLocant());
					}
				}
			}
			else{
				throw new StructureBuildingException("Unsupported stereocentre type for alpha/beta stereochemistry");
			}
		}
		else{
			throw new StructureBuildingException("Unsupported stereocentre type for alpha/beta stereochemistry");
		}
	}


	/**
	 * Applies carbohydate configurational prefixes to the appropriate carbohydrateStem
	 * @param carbohydrateGroup 
	 * @param carbohydrateStereoChemistryEls
	 * @throws StructureBuildingException 
	 */
	private void assignCarbohydratePrefixStereochem(Element carbohydrateGroup, List<Element> carbohydrateStereoChemistryEls) throws StructureBuildingException {
		Fragment carbohydrate = carbohydrateGroup.getFrag();
		Set<Atom> atoms = notExplicitlyDefinedStereoCentreMap.keySet();
		List<Atom> stereocentresInCarbohydrate = new ArrayList<>();
		for (Atom atom : atoms) {
			if (carbohydrate.getAtomByID(atom.getID())!=null){
				Boolean isAnomeric = atom.getProperty(Atom.ISANOMERIC);
				if (isAnomeric ==null || !isAnomeric) {
					stereocentresInCarbohydrate.add(atom);
				}
			}
		}
		//stereoconfiguration is specified from the farthest from C-1 to nearest to C-1
		//but it is easier to set it the other way around hence this reverse
		Collections.reverse(carbohydrateStereoChemistryEls);
		List<String> stereocentreConfiguration = new ArrayList<>();
		for (Element carbohydrateStereoChemistryEl: carbohydrateStereoChemistryEls) {
			String[] values = carbohydrateStereoChemistryEl.getAttributeValue(VALUE_ATR).split("/");
			Collections.addAll(stereocentreConfiguration, values);
		}
		
		if (stereocentresInCarbohydrate.size() != stereocentreConfiguration.size()){
			throw new StructureBuildingException("Disagreement between number of stereocentres on carbohydrate: " + stereocentresInCarbohydrate.size() + " and centres defined by configurational prefixes: " + stereocentreConfiguration.size());
		}
		Collections.sort(stereocentresInCarbohydrate, new FragmentTools.SortByLocants());
		for (int i = 0; i < stereocentresInCarbohydrate.size(); i++) {
			Atom stereoAtom =stereocentresInCarbohydrate.get(i);
			String configuration = stereocentreConfiguration.get(i);
			if (configuration.equals("r")){
				AtomParity atomParity = stereoAtom.getAtomParity();
				if (atomParity ==null){
					throw new RuntimeException("OPSIN bug: stereochemistry was not defined on a carbohydrate stem, but it should been");
				}
				//do nothing, r by default
			}
			else if (configuration.equals("l")){
				AtomParity atomParity = stereoAtom.getAtomParity();
				if (atomParity ==null){
					throw new RuntimeException("OPSIN bug: stereochemistry was not defined on a carbohydrate stem, but it should been");
				}
				atomParity.setParity(-atomParity.getParity());
			}
			else if (configuration.equals("?")){
				stereoAtom.setAtomParity(null);
			}
			else{
				throw new RuntimeException("OPSIN bug: unexpected carbohydrate stereochemistry configuration: " + configuration);
			}
			notExplicitlyDefinedStereoCentreMap.remove(stereoAtom);
		}
	}
	
	private void assignDlStereochem(Element stereoChemistryEl) throws StructureBuildingException {
		String dOrL = stereoChemistryEl.getAttributeValue(VALUE_ATR);
		Element elementToApplyTo = OpsinTools.getNextSiblingIgnoringCertainElements(stereoChemistryEl, new String[]{STEREOCHEMISTRY_EL});
		if (elementToApplyTo != null
				&& elementToApplyTo.getName().equals(GROUP_EL)
				&& attemptAssignmentOfDlStereoToFragment(elementToApplyTo.getFrag(), dOrL)){
			// D/L adjacent to group that now has an appropriate stereocentre e.g. glycine
			return;
		}

		Element parentSubBracketOrRoot = stereoChemistryEl.getParent();
		//generally the LAST group in this list will be the appropriate group
		//we use the same algorithm as for unlocanted substitution so as to deprecate assignment into brackets
		List<Fragment> possibleFragments = StructureBuildingMethods.findAlternativeFragments(parentSubBracketOrRoot);
		List<Element> adjacentGroupEls = OpsinTools.getDescendantElementsWithTagName(parentSubBracketOrRoot, GROUP_EL);
		for (int i = adjacentGroupEls.size()-1; i >=0; i--) {
			possibleFragments.add(adjacentGroupEls.get(i).getFrag());
		}
		for (Fragment fragment : possibleFragments) {
			if (attemptAssignmentOfDlStereoToFragment(fragment, dOrL)) {
				return;
			}
		}
		throw new StereochemistryException("Could not find stereocentre to apply " + dOrL.toUpperCase(Locale.ROOT) + " stereochemistry to");
	}


	private boolean attemptAssignmentOfDlStereoToFragment(Fragment fragment, String dOrL) throws StereochemistryException, StructureBuildingException {
		List<Atom> atomList = fragment.getAtomList();
		for (Atom potentialStereoAtom : atomList) {
			if (notExplicitlyDefinedStereoCentreMap.containsKey(potentialStereoAtom) && potentialStereoAtom.getBondCount() == 4) {
				List<Atom> neighbours = potentialStereoAtom.getAtomNeighbours();
				Atom acidGroup = null;//A carbon connected to non-carbons e.g. COOH
				Atom amineOrAlcohol = null;//N or O e.g. NH2 (as this may be substituted don't check H count)
				Atom sideChain = null;//A carbon
				Atom hydrogen = null;//A hydrogen
				for (Atom atom : neighbours) {
					ChemEl el = atom.getElement();
					if (el == ChemEl.H) {
						hydrogen = atom;
					}
					else if (el == ChemEl.C) {
						int chalcogenNeighbours = 0;
						for (Atom neighbour2 : atom.getAtomNeighbours()) {
							if (atom == neighbour2) {
								continue;
							}
							if (neighbour2.getElement().isChalcogen()) {
								chalcogenNeighbours++;
							}
						}
						if (chalcogenNeighbours > 0) {
							acidGroup = atom;
						}
						else {
							sideChain = atom;
						}
					}
					else if (el == ChemEl.O || el ==ChemEl.N) {
						amineOrAlcohol = atom;
					}
				}
				if (acidGroup != null && amineOrAlcohol != null && sideChain != null && hydrogen != null) {
					Atom[] atomRefs4 = new Atom[]{acidGroup, sideChain, amineOrAlcohol, hydrogen};
					if (dOrL.equals("l") || dOrL.equals("ls")) {
						potentialStereoAtom.setAtomParity(atomRefs4, -1);
					} else if (dOrL.equals("d") || dOrL.equals("ds")) {
						potentialStereoAtom.setAtomParity(atomRefs4, 1);
					} else if (dOrL.equals("dl")) {
						potentialStereoAtom.setAtomParity(atomRefs4, 1);
						potentialStereoAtom.getAtomParity()
								.setStereoGroup(StereoGroup.Rac,
								                ++state.numRacGrps);
					} else{
						throw new RuntimeException("OPSIN bug: Unexpected value for D/L stereochemistry found: " + dOrL );
					}
					notExplicitlyDefinedStereoCentreMap.remove(potentialStereoAtom);
					return true;
				}
			}
		}
		return false;
	}

	static int swapsRequiredToSort(Atom[] atomRefs4){
		Atom[] atomRefs4Copy = atomRefs4.clone();
		int swapsPerformed = 0;
		int i,j;
	
		for (i=atomRefs4Copy.length; --i >=0;) {
			boolean swapped = false;
			for (j=0; j<i;j++) {
				if (atomRefs4Copy[j].getID() > atomRefs4Copy[j+1].getID()){
					Atom temp = atomRefs4Copy[j+1];
					atomRefs4Copy[j+1] = atomRefs4Copy[j];
					atomRefs4Copy[j] = temp;
					swapsPerformed++;
					swapped = true;
				}
			}
			if (!swapped){
				return swapsPerformed;
			}
		}
		return swapsPerformed;
	}
	
	static boolean checkEquivalencyOfAtomsRefs4AndParity(Atom[] atomRefs1, int atomParity1, Atom[] atomRefs2, int atomParity2){
		int swaps1 = swapsRequiredToSort(atomRefs1);
		int swaps2 = swapsRequiredToSort(atomRefs2);
		if (atomParity1 < 0 && atomParity2 > 0 || atomParity1 > 0 && atomParity2 < 0){
			 swaps1++;
		}
		return swaps1 %2 == swaps2 %2;
	}
}

