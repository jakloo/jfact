package test;

import java.util.*;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import uk.ac.manchester.cs.jfact.datatypes.*;
import uk.ac.manchester.cs.jfact.visitors.DLExpressionVisitor;
import uk.ac.manchester.cs.jfact.visitors.DLExpressionVisitorEx;

@SuppressWarnings("javadoc")
public class DatatypeFromXML<R extends Comparable<R>> implements Datatype<R> {
    private String name;
    private String uri;
    private Map<String, DatatypeFromXML<?>> types;
    private Map<String, String> restrictions;
    private Map<String, String> hfpProperties = new HashMap<String, String>();
    private Map<Facet, Comparable> knownFacetValues = new HashMap<Facet, Comparable>();
    private Set<Facet> facets;

    public DatatypeFromXML(String n, Map<String, DatatypeFromXML<?>> types,
            Map<String, String> restrictions) {
        this.name = n;
        if (this.name.startsWith("http://")) {
            this.uri = this.name;
        } else {
            this.uri = DatatypeParser.ns + this.name;
        }
        this.types = types;
        this.restrictions = restrictions;
        this.facets = new HashSet<Facet>();
        this.hfpProperties.put("ordered", "false");
        this.hfpProperties.put("bounded", "false");
        this.hfpProperties.put("cardinality", "countably infinite");
        this.hfpProperties.put("numeric", "false");
    }

    @Override
    public boolean emptyValueSpace() {
        return false;
    }

    public DatatypeFromXML(String n, Map<String, DatatypeFromXML<?>> types,
            Map<String, String> restrictions, String ordered, String bounded,
            String cardinality, String numeric, Collection<Facet> fac) {
        this(n, types, restrictions);
        this.hfpProperties.put("ordered", ordered);
        this.hfpProperties.put("bounded", bounded);
        this.hfpProperties.put("cardinality", cardinality);
        this.hfpProperties.put("numeric", numeric);
        this.facets.addAll(fac);
    }

    public DatatypeFromXML(Element e, Map<String, DatatypeFromXML<?>> typ,
            Map<String, String> restrict) {
        this(e.getAttribute("name"), typ, restrict);
        // this constructor does not use the defaults
        this.hfpProperties.clear();
        NodeList restr = e.getElementsByTagName("xs:restriction");
        for (int i = 0; i < restr.getLength(); i++) {
            String attribute = ((Element) restr.item(i)).getAttribute("base");
            if (attribute != null && attribute.length() > 0) {
                this.restrictions.put(this.uri,
                        attribute.replace("xs:anySimpleType", DatatypeParser.Literal)
                                .replace("xs:", DatatypeParser.ns));
            } else {
                // then it's a sequence of some sort
                // <xs:restriction>
                // <xs:simpleType>
                // <xs:list itemType="xs:NMTOKEN"/>
                String type = ((Element) ((Element) restr.item(i)).getElementsByTagName(
                        "xs:list").item(0)).getAttribute("itemType");
                if (type != null) {
                    this.restrictions.put(this.uri,
                            type.replace("xs:", DatatypeParser.ns));
                }
            }
            // now facets
            NodeList children = restr.item(i).getChildNodes();
            for (int j = 0; j < children.getLength(); j++) {
                Node n = children.item(j);
                if (n instanceof Element) {
                    if (!((Element) n).getTagName().equals("xs:simpleType")) {
                        Facet f = Facets.parse(((Element) n).getTagName());
                        this.knownFacetValues.put(f, ((Element) n).getAttribute("value"));
                    }
                }
            }
        }
        NodeList props = e.getElementsByTagName("hfp:hasProperty");
        for (int i = 0; i < props.getLength(); i++) {
            Element n = (Element) props.item(i);
            this.hfpProperties.put(n.getAttribute("name"), n.getAttribute("value"));
        }
        NodeList facetList = e.getElementsByTagName("hfp:hasFacet");
        for (int i = 0; i < facetList.getLength(); i++) {
            Element n = (Element) facetList.item(i);
            this.facets.add(Facets.parse(n.getAttribute("name")));
        }
        // System.out.println("DatatypeFromXML.DatatypeFromXML() " + uri + "\t"
        // + restrictions);
    }

    @Override
    public void accept(DLExpressionVisitor visitor) {
        visitor.visit(this);
    }

    @Override
    public <O> O accept(DLExpressionVisitorEx<O> visitor) {
        return visitor.visit(this);
    }

    @Override
    public boolean isExpression() {
        return false;
    }

    @Override
    public DatatypeExpression<R> asExpression() {
        return (DatatypeExpression<R>) this;
    }

    @Override
    public Collection<Datatype<?>> getAncestors() {
        Set<Datatype<?>> toReturn = new HashSet<Datatype<?>>();
        // toReturn.add(this.types.get(DatatypeParser.Literal));
        String current = this.restrictions.get(this.uri);
        while (current != null) {
            DatatypeFromXML<?> datatypeFromXML = this.types.get(current);
            if (datatypeFromXML != null) {
                toReturn.add(datatypeFromXML);
            }
            current = this.restrictions.get(current);
        }
        return toReturn;
    }

    @Override
    public boolean getBounded() {
        if (this.hfpProperties.containsKey("bounded")) {
            return Boolean.parseBoolean(this.hfpProperties.get("bounded"));
        } else {
            Datatype<?> ancestor = !this.restrictions.containsKey(this.uri) ? null
                    : this.types.get(this.restrictions.get(this.uri));
            if (ancestor != null) {
                return ancestor.getBounded();
            }
        }
        return false;
    }

    @Override
    public cardinality getCardinality() {
        if (this.hfpProperties.containsKey("cardinality")) {
            return cardinality.parse(this.hfpProperties.get("cardinality"));
        } else {
            Datatype<?> ancestor = !this.restrictions.containsKey(this.uri) ? null
                    : this.types.get(this.restrictions.get(this.uri));
            if (ancestor != null) {
                return ancestor.getCardinality();
            }
        }
        return cardinality.COUNTABLYINFINITE;
    }

    @Override
    public Set<Facet> getFacets() {
        HashSet<Facet> hashSet = new HashSet<Facet>(this.facets);
        String ancestor = this.restrictions.get(this.uri);
        if (ancestor != null) {
            Datatype<?> d = this.types.get(ancestor);
            if (d != null) {
                hashSet.addAll(d.getFacets());
            }
        }
        return hashSet;
    }

    @Override
    public Map<Facet, Comparable> getKnownNonNumericFacetValues() {
        Map<Facet, Comparable> toReturn = new HashMap<Facet, Comparable>(
                this.knownFacetValues);
        String current = this.restrictions.get(this.uri);
        if (current != null) {
            Map<Facet, Comparable> map = this.types.get(current)
                    .getKnownNonNumericFacetValues();
            for (Facet key : map.keySet()) {
                if (!toReturn.containsKey(key)) {
                    toReturn.put(key, map.get(key));
                }
            }
        }
        return toReturn;
    }

    @Override
    public Map<Facet, Comparable> getKnownNumericFacetValues() {
        Map<Facet, Comparable> toReturn = new HashMap<Facet, Comparable>(
                this.knownFacetValues);
        String current = this.restrictions.get(this.uri);
        if (current != null) {
            Map<Facet, Comparable> map = this.types.get(current)
                    .getKnownNumericFacetValues();
            for (Facet key : map.keySet()) {
                if (!toReturn.containsKey(key)) {
                    toReturn.put(key, map.get(key));
                }
            }
        }
        return toReturn;
    }

    @Override
    public <O extends Comparable<O>> O getFacetValue(Facet f) {
        Map<Facet, Comparable> toReturn = this.getKnownFacetValues();
        if (toReturn.containsKey(f)) {
            if (!f.isNumberFacet()) {
                return (O) this.getNumericFacetValue(f);
            }
            return (O) this.getNonNumericFacetValue(f);
        }
        return null;
    }

    @Override
    public ComparableWrapper<? extends Number> getNumericFacetValue(Facet f) {
        Map<Facet, Object> toReturn = this.getKnownFacetValues();
        if (toReturn.containsKey(f)) {
            return (ComparableWrapper<? extends Number>) toReturn.get(f);
        }
        return null;
    }

    @Override
    public boolean getNumeric() {
        if (this.hfpProperties.containsKey("numeric")) {
            return Boolean.parseBoolean(this.hfpProperties.get("numeric"));
        } else {
            Datatype<?> ancestor = !this.restrictions.containsKey(this.uri) ? null
                    : this.types.get(this.restrictions.get(this.uri));
            if (ancestor != null) {
                return ancestor.getNumeric();
            }
        }
        return false;
    }

    @Override
    public ordered getOrdered() {
        if (this.hfpProperties.containsKey("ordered")) {
            return ordered.parse(this.hfpProperties.get("ordered"));
        } else {
            Datatype<?> ancestor = !this.restrictions.containsKey(this.uri) ? null
                    : this.types.get(this.restrictions.get(this.uri));
            if (ancestor != null) {
                return ancestor.getOrdered();
            }
        }
        return ordered.FALSE;
    }

    @Override
    public boolean isCompatible(Datatype<?> type) {
        if (type instanceof NumericDatatype) {
            return type.isCompatible(this);
        }
        return type.getDatatypeURI().equals(this.getDatatypeURI())
                || type.getDatatypeURI().equals(DatatypeParser.Literal)
                || this.isSubType(type) || type.isSubType(this);
    }

    @Override
    public boolean isCompatible(Literal<?> l) {
        return this.isCompatible(l.getDatatypeExpression())
                && this.isInValueSpace(this.parseValue(l.value()));
    }

    @Override
    public boolean isInValueSpace(R l) {
        // TODO verify the constraining facets
        return false;
    }

    @Override
    public R parseValue(String s) {
        return null;
    }

    @Override
    public Literal<R> buildLiteral(final String s) {
        return new Literal<R>() {
            @Override
            public void accept(DLExpressionVisitor visitor) {
                visitor.visit(this);
            }

            @Override
            public <O> O accept(DLExpressionVisitorEx<O> visitor) {
                return visitor.visit(this);
            }

            @Override
            public int compareTo(Literal<R> o) {
                return this.typedValue().compareTo(o.typedValue());
            }

            @Override
            public Datatype<R> getDatatypeExpression() {
                return DatatypeFromXML.this;
            }

            @Override
            public String value() {
                return s;
            }

            @Override
            public R typedValue() {
                return DatatypeFromXML.this.parseValue(s);
            }
        };
    }

    @Override
    public boolean isSubType(Datatype<?> type) {
        if (this.equals(type)) {
            return true;
        }
        Collection<Datatype<?>> ancestors = this.getAncestors();
        boolean contains = ancestors.contains(type);
        // System.out.println("DatatypeFromXML.isSubType() " + ancestors);
        // System.out.println("DatatypeFromXML.isSubType() " + type);
        // System.out.println("DatatypeFromXML.isSubType() " + contains);
        return contains;
    }

    @Override
    public String getDatatypeURI() {
        return this.uri;
    }

    @Override
    public Collection<Literal<R>> listValues() {
        return Collections.emptyList();
    }

    @Override
    public String toString() {
        return "Datatype[" + this.getDatatypeURI() + "]";
    }

    @Override
    public int hashCode() {
        return this.uri.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        if (super.equals(obj)) {
            return true;
        }
        if (obj instanceof Datatype<?>) {
            return this.uri.equals(((Datatype<?>) obj).getDatatypeURI());
        }
        return false;
    }

    @Override
    public boolean isNumericDatatype() {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public NumericDatatype<R> asNumericDatatype() {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public boolean isOrderedDatatype() {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public OrderedDatatype<R> asOrderedDatatype() {
        // TODO Auto-generated method stub
        return null;
    }
}
