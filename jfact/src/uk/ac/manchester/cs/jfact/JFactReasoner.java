package uk.ac.manchester.cs.jfact;

/* This file is part of the JFact DL reasoner
 Copyright 2011-2013 by Ignazio Palmisano, Dmitry Tsarkov, University of Manchester
 This library is free software; you can redistribute it and/or modify it under the terms of the GNU Lesser General Public License as published by the Free Software Foundation; either version 2.1 of the License, or (at your option) any later version.
 This library is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Lesser General Public License for more details.
 You should have received a copy of the GNU Lesser General Public License along with this library; if not, write to the Free Software Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301 USA*/
import java.io.Serializable;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import org.semanticweb.owlapi.model.*;
import org.semanticweb.owlapi.reasoner.*;
import org.semanticweb.owlapi.reasoner.impl.OWLClassNodeSet;
import org.semanticweb.owlapi.reasoner.impl.OWLDataPropertyNode;
import org.semanticweb.owlapi.reasoner.impl.OWLDataPropertyNodeSet;
import org.semanticweb.owlapi.reasoner.impl.OWLObjectPropertyNodeSet;
import org.semanticweb.owlapi.reasoner.knowledgeexploration.OWLKnowledgeExplorerReasoner;
import org.semanticweb.owlapi.util.Version;

import uk.ac.manchester.cs.jfact.datatypes.DatatypeFactory;
import uk.ac.manchester.cs.jfact.helpers.LogAdapter;
import uk.ac.manchester.cs.jfact.kernel.*;
import uk.ac.manchester.cs.jfact.kernel.actors.*;
import uk.ac.manchester.cs.jfact.kernel.dl.IndividualName;
import uk.ac.manchester.cs.jfact.kernel.dl.interfaces.*;
import uk.ac.manchester.cs.jfact.kernel.options.JFactReasonerConfiguration;
import uk.ac.manchester.cs.jfact.kernel.voc.Vocabulary;
import uk.ac.manchester.cs.jfact.split.ModuleType;
import uk.ac.manchester.cs.jfact.split.TOntologyAtom;
import conformance.Original;

/** Synchronization policy: all methods for OWLReasoner are synchronized, except
 * the methods which do not touch the kernel or only affect threadsafe data
 * structures. inner private classes are not synchronized since methods from
 * those classes cannot be invoked from outsize synchronized methods. */
public class JFactReasoner implements OWLReasoner, OWLOntologyChangeListener,
        OWLKnowledgeExplorerReasoner, Serializable {
    private static final long serialVersionUID = 10000L;
    private static final String REASONER_NAME = "JFact";
    private static final Version VERSION = new Version(0, 0, 0, 0);
    protected final AtomicBoolean interrupted = new AtomicBoolean(false);
    private ReasoningKernel kernel;
    private final ExpressionManager em;
    private static final EnumSet<InferenceType> supportedInferenceTypes = EnumSet.of(
            InferenceType.CLASS_ASSERTIONS, InferenceType.CLASS_HIERARCHY,
            InferenceType.DATA_PROPERTY_HIERARCHY,
            InferenceType.OBJECT_PROPERTY_HIERARCHY, InferenceType.SAME_INDIVIDUAL);
    private final OWLOntologyManager manager;
    private final OWLOntology root;
    private final BufferingMode bufferingMode;
    @SuppressWarnings("rawtypes")
    private final List<OWLOntologyChange> rawChanges = new ArrayList<OWLOntologyChange>();
    private final Set<OWLAxiom> reasonerAxioms = new LinkedHashSet<OWLAxiom>();
    @Original
    private final JFactReasonerConfiguration configuration;
    private final OWLDataFactory df;
    protected TranslationMachinery translationMachinery;
    // holds the consistency status: true for consistent, false for
    // inconsistent, null for not verified (or changes received)
    private Boolean consistencyVerified = null;
    private final Set<OWLEntity> knownEntities = new HashSet<OWLEntity>();
    private final DatatypeFactory datatypeFactory;

    /** @param o
     * @param c
     * @param b */
    public JFactReasoner(OWLOntology o, OWLReasonerConfiguration c, BufferingMode b) {
        this(o, c instanceof JFactReasonerConfiguration ? (JFactReasonerConfiguration) c
                : new JFactReasonerConfiguration(c), b);
    }

    /** @param rootOntology
     * @param config
     * @param bufferingMode */
    public JFactReasoner(OWLOntology rootOntology, JFactReasonerConfiguration config,
            BufferingMode bufferingMode) {
        configuration = config;
        root = rootOntology;
        df = root.getOWLOntologyManager().getOWLDataFactory();
        datatypeFactory = DatatypeFactory.getInstance();
        kernel = new ReasoningKernel(configuration, datatypeFactory);
        em = kernel.getExpressionManager();
        this.bufferingMode = bufferingMode;
        manager = root.getOWLOntologyManager();
        knownEntities.add(df.getOWLThing());
        knownEntities.add(df.getOWLNothing());
        for (OWLOntology ont : root.getImportsClosure()) {
            for (OWLAxiom ax : ont.getLogicalAxioms()) {
                OWLAxiom axiom = ax.getAxiomWithoutAnnotations();
                reasonerAxioms.add(axiom);
                knownEntities.addAll(axiom.getSignature());
            }
            for (OWLAxiom ax : ont.getAxioms(AxiomType.DECLARATION)) {
                OWLAxiom axiom = ax.getAxiomWithoutAnnotations();
                reasonerAxioms.add(axiom);
                knownEntities.addAll(axiom.getSignature());
            }
        }
        kernel.setTopBottomRoleNames(Vocabulary.TOP_OBJECT_PROPERTY,
                Vocabulary.BOTTOM_OBJECT_PROPERTY, Vocabulary.TOP_DATA_PROPERTY,
                Vocabulary.BOTTOM_DATA_PROPERTY);
        kernel.setInterruptedSwitch(interrupted);
        configuration.getProgressMonitor().reasonerTaskStarted(
                ReasonerProgressMonitor.LOADING);
        configuration.getProgressMonitor().reasonerTaskBusy();
        kernel.clearKB();
        translationMachinery = new TranslationMachinery(kernel, df, datatypeFactory);
        translationMachinery.loadAxioms(reasonerAxioms);
        configuration.getProgressMonitor().reasonerTaskStopped();
    }

    /** @return ontology */
    public Ontology getOntology() {
        return kernel.getOntology();
    }

    @Override
    public synchronized Node<OWLClass> getEquivalentClasses(OWLClassExpression ce)
            throws InconsistentOntologyException, ClassExpressionNotInProfileException,
            ReasonerInterruptedException, TimeOutException {
        Collection<ConceptExpression> pointers;
        if (isFreshName(ce)) {
            pointers = Collections.emptyList();
        } else {
            checkConsistency();
            TaxonomyActor<ConceptExpression> actor = new TaxonomyActor<ConceptExpression>(
                    em, new ClassPolicy());
            kernel.getEquivalentConcepts(translationMachinery.toClassPointer(ce), actor);
            pointers = actor.getSynonyms();
        }
        return translationMachinery.getClassExpressionTranslator().getNodeFromPointers(
                pointers);
    }

    private boolean isFreshName(OWLClassExpression ce) {
        if (ce.isAnonymous()) {
            return false;
        }
        return !knownEntities.contains(ce.asOWLClass());
    }

    @Override
    @SuppressWarnings("rawtypes")
    public void ontologiesChanged(List<? extends OWLOntologyChange> changes)
            throws OWLException {
        handleRawOntologyChanges(changes);
    }

    @Override
    public BufferingMode getBufferingMode() {
        return bufferingMode;
    }

    @Override
    public long getTimeOut() {
        return configuration.getTimeOut();
    }

    @Override
    public OWLOntology getRootOntology() {
        return root;
    }

    @SuppressWarnings("rawtypes")
    private void handleRawOntologyChanges(List<? extends OWLOntologyChange> changes) {
        rawChanges.addAll(changes);
        // We auto-flush the changes if the reasoner is non-buffering
        if (bufferingMode.equals(BufferingMode.NON_BUFFERING)) {
            flush();
        }
    }

    @Override
    @SuppressWarnings("rawtypes")
    public synchronized List<OWLOntologyChange> getPendingChanges() {
        return new ArrayList<OWLOntologyChange>(rawChanges);
    }

    @Override
    public synchronized Set<OWLAxiom> getPendingAxiomAdditions() {
        if (!rawChanges.isEmpty()) {
            Set<OWLAxiom> added = new HashSet<OWLAxiom>();
            computeDiff(added, new HashSet<OWLAxiom>());
            return added;
        }
        return Collections.emptySet();
    }

    @Override
    public synchronized Set<OWLAxiom> getPendingAxiomRemovals() {
        if (!rawChanges.isEmpty()) {
            Set<OWLAxiom> removed = new HashSet<OWLAxiom>();
            computeDiff(new HashSet<OWLAxiom>(), removed);
            return removed;
        }
        return Collections.emptySet();
    }

    @Override
    public synchronized void flush() {
        // Process the changes
        if (!rawChanges.isEmpty()) {
            Set<OWLAxiom> added = new HashSet<OWLAxiom>();
            Set<OWLAxiom> removed = new HashSet<OWLAxiom>();
            computeDiff(added, removed);
            rawChanges.clear();
            if (!added.isEmpty() || !removed.isEmpty()) {
                reasonerAxioms.removeAll(removed);
                reasonerAxioms.addAll(added);
                knownEntities.clear();
                for (OWLAxiom ax : reasonerAxioms) {
                    knownEntities.addAll(ax.getSignature());
                }
                // set the consistency status to not verified
                consistencyVerified = null;
                handleChanges(added, removed);
            }
        }
    }

    /** Computes a diff of what axioms have been added and what axioms have been
     * removed from the list of pending changes. Note that even if the list of
     * pending changes is non-empty then there may be no changes for the
     * reasoner to deal with.
     * 
     * @param added
     *            The logical axioms that have been added to the imports closure
     *            of the reasoner root ontology
     * @param removed
     *            The logical axioms that have been removed from the imports
     *            closure of the reasoner root ontology */
    private synchronized void computeDiff(Set<OWLAxiom> added, Set<OWLAxiom> removed) {
        for (OWLOntologyChange<?> change : rawChanges) {
            if (change.isAddAxiom()) {
                OWLAxiom ax = change.getAxiom();
                if (!reasonerAxioms.contains(ax.getAxiomWithoutAnnotations())) {
                    added.add(ax);
                }
            } else if (change.isRemoveAxiom()) {
                removed.add(change.getAxiom());
            }
        }
        added.removeAll(removed);
    }

    @Override
    public FreshEntityPolicy getFreshEntityPolicy() {
        return configuration.getFreshEntityPolicy();
    }

    @Override
    public IndividualNodeSetPolicy getIndividualNodeSetPolicy() {
        return configuration.getIndividualNodeSetPolicy();
    }

    /** Asks the reasoner implementation to handle axiom additions and removals
     * from the imports closure of the root ontology. The changes will not
     * include annotation axiom additions and removals.
     * 
     * @param addAxioms
     *            The axioms to be added to the reasoner.
     * @param removeAxioms
     *            The axioms to be removed from the reasoner */
    private synchronized void handleChanges(Set<OWLAxiom> addAxioms,
            Set<OWLAxiom> removeAxioms) {
        translationMachinery.loadAxioms(addAxioms);
        for (OWLAxiom ax_r : removeAxioms) {
            translationMachinery.retractAxiom(ax_r);
        }
    }

    @Override
    public String getReasonerName() {
        return REASONER_NAME;
    }

    @Override
    public Version getReasonerVersion() {
        return VERSION;
    }

    @Override
    public void interrupt() {
        interrupted.set(true);
    }

    // precompute inferences
    @Override
    public synchronized void precomputeInferences(InferenceType... inferenceTypes)
            throws ReasonerInterruptedException, TimeOutException,
            InconsistentOntologyException {
        for (InferenceType it : inferenceTypes) {
            if (supportedInferenceTypes.contains(it)) {
                if (!kernel.isKBRealised()) {
                    kernel.realiseKB();
                }
                return;
            }
        }
    }

    @Override
    public boolean isPrecomputed(InferenceType inferenceType) {
        if (supportedInferenceTypes.contains(inferenceType)) {
            return kernel.isKBRealised();
        }
        return true;
    }

    @Override
    public Set<InferenceType> getPrecomputableInferenceTypes() {
        return supportedInferenceTypes;
    }

    // consistency
    @Override
    public synchronized boolean isConsistent() throws ReasonerInterruptedException,
            TimeOutException {
        if (consistencyVerified == null) {
            try {
                consistencyVerified = kernel.isKBConsistent();
            } catch (InconsistentOntologyException e) {
                consistencyVerified = Boolean.FALSE;
            }
        }
        return consistencyVerified;
    }

    private void checkConsistency() {
        if (interrupted.get()) {
            throw new ReasonerInterruptedException();
        }
        if (!isConsistent()) {
            throw new InconsistentOntologyException();
        }
    }

    @Override
    public synchronized boolean isSatisfiable(OWLClassExpression classExpression)
            throws ReasonerInterruptedException, TimeOutException,
            ClassExpressionNotInProfileException, FreshEntitiesException,
            InconsistentOntologyException {
        checkConsistency();
        return kernel.isSatisfiable(translationMachinery.toClassPointer(classExpression));
    }

    @Override
    public Node<OWLClass> getUnsatisfiableClasses() throws ReasonerInterruptedException,
            TimeOutException, InconsistentOntologyException {
        return getBottomClassNode();
    }

    // entailments
    @Override
    public synchronized boolean isEntailed(OWLAxiom axiom)
            throws ReasonerInterruptedException, UnsupportedEntailmentTypeException,
            TimeOutException, AxiomNotInProfileException, FreshEntitiesException,
            InconsistentOntologyException {
        checkConsistency();
        if (reasonerAxioms.contains(axiom.getAxiomWithoutAnnotations())) {
            return true;
        }
        try {
            return axiom.accept(translationMachinery.getEntailmentChecker());
        } catch (ReasonerFreshEntityException e) {
            String iri = e.getIri();
            if (getFreshEntityPolicy() == FreshEntityPolicy.DISALLOW) {
                for (OWLEntity o : axiom.getSignature()) {
                    if (o.getIRI().toString().equals(iri)) {
                        throw new FreshEntitiesException(o, e);
                    }
                }
                throw new FreshEntitiesException(axiom.getSignature(), e);
            }
            System.out
                    .println("JFactReasoner.isEntailed() WARNING: fresh entity exception in the reasoner for entity: "
                            + iri + "; defaulting to axiom not entailed");
            return false;
        }
    }

    @Override
    public synchronized boolean isEntailed(Set<? extends OWLAxiom> axioms)
            throws ReasonerInterruptedException, UnsupportedEntailmentTypeException,
            TimeOutException, AxiomNotInProfileException, FreshEntitiesException,
            InconsistentOntologyException {
        for (OWLAxiom ax : axioms) {
            if (!this.isEntailed(ax)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean isEntailmentCheckingSupported(AxiomType<?> axiomType) {
        if (axiomType.equals(AxiomType.SWRL_RULE)) {
            return false;
        }
        return true;
    }

    // tracing
    /** @param axiom
     * @return tracing set (set of axioms that were participate in achieving
     *         result) for a given entailment. Return empty set if the axiom is
     *         not entailed. */
    public synchronized Set<OWLAxiom> getTrace(OWLAxiom axiom) {
        kernel.needTracing();
        if (this.isEntailed(axiom)) {
            return translationMachinery.translateTAxiomSet(kernel.getTrace());
        }
        return Collections.emptySet();
    }

    // classes
    @Override
    public Node<OWLClass> getTopClassNode() {
        return getEquivalentClasses(df.getOWLThing());
    }

    @Override
    public Node<OWLClass> getBottomClassNode() {
        return getEquivalentClasses(df.getOWLNothing());
    }

    @Override
    public synchronized NodeSet<OWLClass> getSubClasses(OWLClassExpression ce,
            boolean direct) throws ReasonerInterruptedException, TimeOutException,
            FreshEntitiesException, InconsistentOntologyException {
        if (isFreshName(ce)) {
            if (configuration.getFreshEntityPolicy() == FreshEntityPolicy.DISALLOW) {
                throw new FreshEntitiesException(ce.getSignature());
            }
            return new OWLClassNodeSet(getBottomClassNode());
        }
        checkConsistency();
        TaxonomyActor<ConceptExpression> actor = new TaxonomyActor<ConceptExpression>(em,
                new ClassPolicy());
        kernel.getSubConcepts(translationMachinery.toClassPointer(ce), direct, actor);
        Collection<Collection<ConceptExpression>> pointers = actor.getElements();
        return translationMachinery.getClassExpressionTranslator()
                .getNodeSetFromPointers(pointers);
    }

    @Override
    public synchronized NodeSet<OWLClass> getSuperClasses(OWLClassExpression ce,
            boolean direct) throws InconsistentOntologyException,
            ClassExpressionNotInProfileException, ReasonerInterruptedException,
            TimeOutException {
        if (isFreshName(ce)) {
            return new OWLClassNodeSet(getTopClassNode());
        }
        checkConsistency();
        return translationMachinery.getClassExpressionTranslator()
                .getNodeSetFromPointers(
                        askSuperClasses(translationMachinery.toClassPointer(ce), direct));
    }

    @Override
    public synchronized NodeSet<OWLClass> getDisjointClasses(OWLClassExpression ce) {
        TaxonomyActor<ConceptExpression> actor = new TaxonomyActor<ConceptExpression>(em,
                new ClassPolicy());
        ConceptExpression p = translationMachinery.toClassPointer(ce);
        kernel.getDisjointConcepts(p, actor);
        return translationMachinery.getClassExpressionTranslator()
                .getNodeSetFromPointers(actor.getElements());
    }

    // object properties
    @Override
    public Node<OWLObjectPropertyExpression> getTopObjectPropertyNode() {
        return getEquivalentObjectProperties(df.getOWLTopObjectProperty());
    }

    @Override
    public Node<OWLObjectPropertyExpression> getBottomObjectPropertyNode() {
        return getEquivalentObjectProperties(df.getOWLBottomObjectProperty());
    }

    @Override
    public synchronized NodeSet<OWLObjectPropertyExpression> getSubObjectProperties(
            OWLObjectPropertyExpression pe, boolean direct)
            throws InconsistentOntologyException, ReasonerInterruptedException,
            TimeOutException {
        checkConsistency();
        TaxonomyActor<ObjectRoleExpression> actor = new TaxonomyActor<ObjectRoleExpression>(
                em, new ObjectPropertyPolicy());
        kernel.getSubRoles(translationMachinery.toObjectPropertyPointer(pe), direct,
                actor);
        return translationMachinery.getObjectPropertyTranslator().getNodeSetFromPointers(
                actor.getElements());
    }

    @Override
    public synchronized NodeSet<OWLObjectPropertyExpression> getSuperObjectProperties(
            OWLObjectPropertyExpression pe, boolean direct)
            throws InconsistentOntologyException, ReasonerInterruptedException,
            TimeOutException {
        checkConsistency();
        TaxonomyActor<ObjectRoleExpression> actor = new TaxonomyActor<ObjectRoleExpression>(
                em, new ObjectPropertyPolicy());
        kernel.getSupRoles(translationMachinery.toObjectPropertyPointer(pe), direct,
                actor);
        return translationMachinery.getObjectPropertyTranslator().getNodeSetFromPointers(
                actor.getElements());
    }

    @Override
    public synchronized Node<OWLObjectPropertyExpression> getEquivalentObjectProperties(
            OWLObjectPropertyExpression pe) throws InconsistentOntologyException,
            ReasonerInterruptedException, TimeOutException {
        checkConsistency();
        TaxonomyActor<ObjectRoleExpression> actor = new TaxonomyActor<ObjectRoleExpression>(
                em, new ObjectPropertyPolicy());
        kernel.getEquivalentRoles(translationMachinery.toObjectPropertyPointer(pe), actor);
        return translationMachinery.getObjectPropertyTranslator().getNodeFromPointers(
                actor.getSynonyms());
    }

    @Override
    public synchronized NodeSet<OWLObjectPropertyExpression> getDisjointObjectProperties(
            OWLObjectPropertyExpression pe) throws InconsistentOntologyException,
            ReasonerInterruptedException, TimeOutException {
        checkConsistency();
        // TODO: incomplete
        OWLObjectPropertyNodeSet toReturn = new OWLObjectPropertyNodeSet();
        toReturn.addNode(getBottomObjectPropertyNode());
        return toReturn;
    }

    @Override
    public Node<OWLObjectPropertyExpression> getInverseObjectProperties(
            OWLObjectPropertyExpression pe) throws InconsistentOntologyException,
            ReasonerInterruptedException, TimeOutException {
        return getEquivalentObjectProperties(pe.getInverseProperty());
    }

    @Override
    public synchronized NodeSet<OWLClass> getObjectPropertyDomains(
            OWLObjectPropertyExpression pe, boolean direct)
            throws InconsistentOntologyException, ReasonerInterruptedException,
            TimeOutException {
        checkConsistency();
        ConceptExpression subClass = translationMachinery.toClassPointer(df
                .getOWLObjectSomeValuesFrom(pe, df.getOWLThing()));
        return translationMachinery.getClassExpressionTranslator()
                .getNodeSetFromPointers(askSuperClasses(subClass, direct));
    }

    @Override
    public NodeSet<OWLClass> getObjectPropertyRanges(OWLObjectPropertyExpression pe,
            boolean direct) throws InconsistentOntologyException,
            ReasonerInterruptedException, TimeOutException {
        return getSuperClasses(
                df.getOWLObjectSomeValuesFrom(pe.getInverseProperty(), df.getOWLThing()),
                direct);
    }

    // data properties
    @Override
    public Node<OWLDataProperty> getTopDataPropertyNode() {
        OWLDataPropertyNode toReturn = new OWLDataPropertyNode();
        toReturn.add(df.getOWLTopDataProperty());
        return toReturn;
    }

    @Override
    public Node<OWLDataProperty> getBottomDataPropertyNode() {
        OWLDataPropertyNode toReturn = new OWLDataPropertyNode();
        toReturn.add(df.getOWLBottomDataProperty());
        return toReturn;
        // XXX both this and the previous method look like hacks
        // return getEquivalentDataProperties(df.getOWLBottomDataProperty());
    }

    @Override
    public synchronized NodeSet<OWLDataProperty> getSubDataProperties(OWLDataProperty pe,
            boolean direct) throws InconsistentOntologyException,
            ReasonerInterruptedException, TimeOutException {
        checkConsistency();
        TaxonomyActor<DataRoleExpression> actor = new TaxonomyActor<DataRoleExpression>(
                em, new DataPropertyPolicy());
        kernel.getSubRoles(translationMachinery.toDataPropertyPointer(pe), direct, actor);
        return translationMachinery.getDataPropertyTranslator().getNodeSetFromPointers(
                actor.getElements());
    }

    @Override
    public synchronized NodeSet<OWLDataProperty> getSuperDataProperties(
            OWLDataProperty pe, boolean direct) throws InconsistentOntologyException,
            ReasonerInterruptedException, TimeOutException {
        checkConsistency();
        TaxonomyActor<DataRoleExpression> actor = new TaxonomyActor<DataRoleExpression>(
                em, new DataPropertyPolicy());
        kernel.getSupRoles(translationMachinery.toDataPropertyPointer(pe), direct, actor);
        Collection<Collection<DataRoleExpression>> properties = actor.getElements();
        return translationMachinery.getDataPropertyTranslator().getNodeSetFromPointers(
                properties);
    }

    @Override
    public synchronized Node<OWLDataProperty> getEquivalentDataProperties(
            OWLDataProperty pe) throws InconsistentOntologyException,
            ReasonerInterruptedException, TimeOutException {
        checkConsistency();
        DataRoleExpression p = translationMachinery.toDataPropertyPointer(pe);
        TaxonomyActor<DataRoleExpression> actor = new TaxonomyActor<DataRoleExpression>(
                em, new DataPropertyPolicy());
        kernel.getEquivalentRoles(p, actor);
        Collection<DataRoleExpression> dataPropertySynonyms = actor.getSynonyms();
        return translationMachinery.getDataPropertyTranslator().getNodeFromPointers(
                dataPropertySynonyms);
    }

    @Override
    public synchronized NodeSet<OWLDataProperty> getDisjointDataProperties(
            OWLDataPropertyExpression pe) throws InconsistentOntologyException,
            ReasonerInterruptedException, TimeOutException {
        checkConsistency();
        // TODO: incomplete
        OWLDataPropertyNodeSet toReturn = new OWLDataPropertyNodeSet();
        toReturn.addNode(getBottomDataPropertyNode());
        return toReturn;
    }

    @Override
    public NodeSet<OWLClass> getDataPropertyDomains(OWLDataProperty pe, boolean direct)
            throws InconsistentOntologyException, ReasonerInterruptedException,
            TimeOutException {
        return getSuperClasses(df.getOWLDataSomeValuesFrom(pe, df.getTopDatatype()),
                direct);
    }

    // individuals
    @Override
    public synchronized NodeSet<OWLClass>
            getTypes(OWLNamedIndividual ind, boolean direct)
                    throws InconsistentOntologyException, ReasonerInterruptedException,
                    TimeOutException {
        checkConsistency();
        TaxonomyActor<ConceptExpression> actor = new TaxonomyActor<ConceptExpression>(em,
                new ClassPolicy());
        kernel.getTypes(translationMachinery.toIndividualPointer(ind), direct, actor);
        Collection<Collection<ConceptExpression>> classElements = actor.getElements();
        return translationMachinery.getClassExpressionTranslator()
                .getNodeSetFromPointers(classElements);
    }

    @Override
    public synchronized NodeSet<OWLNamedIndividual> getInstances(OWLClassExpression ce,
            boolean direct) throws InconsistentOntologyException,
            ClassExpressionNotInProfileException, ReasonerInterruptedException,
            TimeOutException {
        checkConsistency();
        TaxonomyActor<IndividualExpression> actor = new TaxonomyActor<IndividualExpression>(
                em, new IndividualPolicy(true));
        kernel.getInstances(translationMachinery.toClassPointer(ce), actor, direct);
        return translationMachinery.translateIndividualPointersToNodeSet(actor
                .getElements().iterator().next());
    }

    @Override
    public synchronized NodeSet<OWLNamedIndividual> getObjectPropertyValues(
            OWLNamedIndividual ind, OWLObjectPropertyExpression pe)
            throws InconsistentOntologyException, ReasonerInterruptedException,
            TimeOutException {
        checkConsistency();
        List<Individual> fillers = kernel.getRoleFillers(
                translationMachinery.toIndividualPointer(ind),
                translationMachinery.toObjectPropertyPointer(pe));
        List<IndividualExpression> acc = new ArrayList<IndividualExpression>();
        for (NamedEntry p : fillers) {
            acc.add(em.individual(p.getName()));
        }
        return translationMachinery.translateIndividualPointersToNodeSet(acc);
    }

    @Override
    public synchronized Set<OWLLiteral> getDataPropertyValues(OWLNamedIndividual ind,
            OWLDataProperty pe) throws InconsistentOntologyException,
            ReasonerInterruptedException, TimeOutException {
        checkConsistency();
        // for(DataRoleExpression e:
        // askDataProperties(translationMachinery.toIndividualPointer(ind))) {
        //
        // }
        // return kernel.getDRM().
        // return translationMachinery
        // .translateIndividualPointersToNodeSet(askRelatedIndividuals(
        // translationMachinery.toIndividualPointer(ind),
        // translationMachinery.toDataPropertyPointer(pe)));
        // TODO:
        return Collections.emptySet();
    }

    @Override
    public synchronized Node<OWLNamedIndividual>
            getSameIndividuals(OWLNamedIndividual ind)
                    throws InconsistentOntologyException, ReasonerInterruptedException,
                    TimeOutException {
        checkConsistency();
        TaxonomyActor<IndividualName> actor = new TaxonomyActor<IndividualName>(em,
                new IndividualPolicy(true));
        kernel.getSameAs(translationMachinery.toIndividualPointer(ind), actor);
        return translationMachinery.getIndividualTranslator().getNodeFromPointers(
                actor.getSynonyms());
    }

    @Override
    public NodeSet<OWLNamedIndividual> getDifferentIndividuals(OWLNamedIndividual ind)
            throws InconsistentOntologyException, ReasonerInterruptedException,
            TimeOutException {
        OWLClassExpression ce = df.getOWLObjectOneOf(ind).getObjectComplementOf();
        return getInstances(ce, false);
    }

    @Override
    public synchronized void dispose() {
        manager.removeOntologyChangeListener(this);
        translationMachinery = null;
        kernel = null;
    }

    /** @param pw
     * @param includeBottomNode */
    public void dumpClassHierarchy(LogAdapter pw, boolean includeBottomNode) {
        dumpSubClasses(getTopClassNode(), pw, 0, includeBottomNode);
    }

    private void dumpSubClasses(Node<OWLClass> node, LogAdapter pw, int depth,
            boolean includeBottomNode) {
        if (includeBottomNode || !node.isBottomNode()) {
            for (int i = 0; i < depth; i++) {
                pw.print("    ");
            }
            pw.print(node);
            pw.println();
            for (Node<OWLClass> sub : getSubClasses(node.getRepresentativeElement(), true)) {
                dumpSubClasses(sub, pw, depth + 1, includeBottomNode);
            }
        }
    }

    private Collection<Collection<ConceptExpression>> askSuperClasses(
            ConceptExpression arg, boolean direct) {
        TaxonomyActor<ConceptExpression> actor = new TaxonomyActor<ConceptExpression>(em,
                new ClassPolicy());
        kernel.getSupConcepts(arg, direct, actor);
        return actor.getElements();
    }

    /** @param time */
    public synchronized void writeReasoningResult(long time) {
        kernel.writeReasoningResult(time);
    }

    // owl knowledge exploration
    private class RootNodeImpl implements RootNode, Serializable {
        private static final long serialVersionUID = 11000L;
        private final DlCompletionTree pointer;

        public RootNodeImpl(DlCompletionTree p) {
            pointer = p;
        }

        @SuppressWarnings("unchecked")
        @Override
        public <T> T getNode() {
            return (T) pointer;
        }
    }

    @Override
    public RootNode getRoot(OWLClassExpression expression) {
        return new RootNodeImpl(kernel.buildCompletionTree(translationMachinery
                .toClassPointer(expression)));
    }

    @Override
    public Node<? extends OWLObjectPropertyExpression> getObjectNeighbours(
            RootNode object, boolean deterministicOnly) {
        List<ObjectRoleExpression> ret = new ArrayList<ObjectRoleExpression>();
        for (RoleExpression p : kernel.getObjectRoles(
                (DlCompletionTree) object.getNode(), deterministicOnly, false)) {
            ret.add((ObjectRoleExpression) p);
        }
        return translationMachinery.getObjectPropertyTranslator()
                .getNodeFromPointers(ret);
    }

    @Override
    public Node<OWLDataProperty> getDataNeighbours(RootNode object,
            boolean deterministicOnly) {
        List<DataRoleExpression> ret = new ArrayList<DataRoleExpression>();
        for (RoleExpression p : kernel.getDataRoles((DlCompletionTree) object.getNode(),
                deterministicOnly)) {
            ret.add((DataRoleExpression) p);
        }
        return translationMachinery.getDataPropertyTranslator().getNodeFromPointers(ret);
    }

    @Override
    public Collection<RootNode>
            getObjectNeighbours(RootNode n, OWLObjectProperty property) {
        List<RootNode> toReturn = new ArrayList<RootNode>();
        for (DlCompletionTree t : kernel.getNeighbours((DlCompletionTree) n.getNode(),
                translationMachinery.toObjectPropertyPointer(property))) {
            toReturn.add(new RootNodeImpl(t));
        }
        return toReturn;
    }

    @Override
    public Collection<RootNode> getDataNeighbours(RootNode n, OWLDataProperty property) {
        List<RootNode> toReturn = new ArrayList<RootNode>();
        for (DlCompletionTree t : kernel.getNeighbours((DlCompletionTree) n.getNode(),
                translationMachinery.toDataPropertyPointer(property))) {
            toReturn.add(new RootNodeImpl(t));
        }
        return toReturn;
    }

    @Override
    public Node<? extends OWLClassExpression> getObjectLabel(RootNode object,
            boolean deterministicOnly) {
        Node<OWLClass> nodeFromPointers = translationMachinery
                .getClassExpressionTranslator().getNodeFromPointers(
                        kernel.getObjectLabel((DlCompletionTree) object.getNode(),
                                deterministicOnly));
        return nodeFromPointers;
    }

    @Override
    public Node<? extends OWLDataRange> getDataLabel(RootNode object,
            boolean deterministicOnly) {
        return translationMachinery.getDataRangeTranslator().getNodeFromPointers(
                kernel.getDataLabel((DlCompletionTree) object.getNode(),
                        deterministicOnly));
    }

    @Override
    public RootNode getBlocker(RootNode object) {
        return new RootNodeImpl(kernel.getBlocker((DlCompletionTree) object.getNode()));
    }

    /** @param useSemantics
     * @param type
     * @return number of atoms */
    public int getAtomicDecompositionSize(boolean useSemantics, ModuleType type) {
        return kernel.getAtomicDecompositionSize(useSemantics, type);
    }

    /** @return set of tautologies */
    public Set<OWLAxiom> getTautologies() {
        Set<OWLAxiom> toReturn = new HashSet<OWLAxiom>();
        for (AxiomInterface ax : kernel.getTautologies()) {
            if (ax.getOWLAxiom() != null) {
                toReturn.add(ax.getOWLAxiom());
            }
        }
        return toReturn;
    }

    /** @param index
     * @return set of axioms that corresponds to the atom with the id INDEX */
    public Set<OWLAxiom> getAtomAxioms(int index) {
        return axiomsToSet(kernel.getAtomAxioms(index));
    }

    private Set<OWLAxiom> axiomsToSet(Collection<AxiomInterface> index) {
        Set<OWLAxiom> toReturn = new HashSet<OWLAxiom>();
        for (AxiomInterface ax : index) {
            if (ax.getOWLAxiom() != null) {
                toReturn.add(ax.getOWLAxiom());
            }
        }
        return toReturn;
    }

    /** @param index
     * @return set of axioms that corresponds to the module of the atom with the
     *         id INDEX */
    public Set<OWLAxiom> getAtomModule(int index) {
        return axiomsToSet(kernel.getAtomModule(index));
    }

    /** @return number of locality checks performed for Ad creation */
    public long getLocCheckNumber() {
        return kernel.getLocCheckNumber();
    }

    /** @param index
     * @return set of atoms on which atom with index INDEX depends */
    public Set<TOntologyAtom> getAtomDependents(int index) {
        return kernel.getAtomDependents(index);
    }

    class EntityVisitorEx implements OWLEntityVisitorEx<Expression>, Serializable {
        private static final long serialVersionUID = 11000L;

        @Override
        public Expression visit(OWLClass cls) {
            return translationMachinery.toClassPointer(cls);
        }

        @Override
        public Expression visit(OWLObjectProperty property) {
            return translationMachinery.toObjectPropertyPointer(property);
        }

        @Override
        public Expression visit(OWLDataProperty property) {
            return translationMachinery.toDataPropertyPointer(property);
        }

        @Override
        public Expression visit(OWLNamedIndividual individual) {
            return translationMachinery.toIndividualPointer(individual);
        }

        @Override
        public Expression visit(OWLDatatype datatype) {
            return null;
        }

        @Override
        public Expression visit(OWLAnnotationProperty property) {
            return null;
        }
    }

    private final EntityVisitorEx entityTranslator = new EntityVisitorEx();

    /** @param signature
     * @param useSemantic
     * @param moduletype
     * @return set of axioms in the module */
    public Set<OWLAxiom> getModule(Set<OWLEntity> signature, boolean useSemantic,
            ModuleType moduletype) {
        List<Expression> list = new ArrayList<Expression>();
        for (OWLEntity entity : signature) {
            if (entity instanceof OWLLogicalEntity) {
                Expression ex = entity.accept(entityTranslator);
                if (ex != null) {
                    list.add(ex);
                }
            }
        }
        List<AxiomInterface> axioms = kernel.getModule(list, useSemantic, moduletype);
        Set<OWLAxiom> toReturn = new HashSet<OWLAxiom>();
        for (AxiomInterface ax : axioms) {
            if (ax.getOWLAxiom() != null) {
                toReturn.add(ax.getOWLAxiom());
            }
        }
        return toReturn;
    }

    /** @param signature
     * @param useSemantic
     * @param moduletype
     * @return set of non local axioms */
    public Set<OWLAxiom> getNonLocal(Set<OWLEntity> signature, boolean useSemantic,
            ModuleType moduletype) {
        List<Expression> list = new ArrayList<Expression>();
        for (OWLEntity entity : signature) {
            if (entity instanceof OWLLogicalEntity) {
                Expression ex = entity.accept(entityTranslator);
                if (ex != null) {
                    list.add(ex);
                }
            }
        }
        Set<AxiomInterface> axioms = kernel.getNonLocal(list, useSemantic, moduletype);
        Set<OWLAxiom> toReturn = new HashSet<OWLAxiom>();
        for (AxiomInterface ax : axioms) {
            if (ax.getOWLAxiom() != null) {
                toReturn.add(ax.getOWLAxiom());
            }
        }
        return toReturn;
    }

/**
     * get all individuals from the set individuals that has r-successor and
     * s-successor and those are related via OP: r op s
     *
     * @param individuals
     *            set of individuals to choose from
     * @param r
     *            first operand of the comparison
     * @param s
     *            second operand of the comparison
     * @param op
     *            comparison operation: 0 means "==", 1 means "!=", 2 means "<", 3 means
     *            "<=", 4 means ">", 5 means ">="
     * @return data related individuals
     */
    public synchronized Node<OWLNamedIndividual> getDataRelatedIndividuals(
            Set<OWLIndividual> individuals, OWLDataProperty r, OWLDataProperty s, int op) {
        checkConsistency();
        // load all the individuals as parameters
        return translationMachinery.getIndividualTranslator().getNodeFromPointers(
                kernel.getDataRelatedIndividuals(
                        translationMachinery.toDataPropertyPointer(r),
                        translationMachinery.toDataPropertyPointer(s), op,
                        translationMachinery.translateIndividualSet(individuals)));
    }
}
