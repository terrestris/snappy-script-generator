package de.terrestris.snappy;

import org.apache.commons.io.IOUtils;
import org.apache.logging.log4j.Logger;

import javax.xml.namespace.QName;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.*;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

import static org.apache.logging.log4j.LogManager.getLogger;
import static org.deegree.commons.xml.stax.XMLStreamUtils.moveReaderToFirstMatch;

public class SnappyScriptGenerator {

    private static Logger LOG = getLogger(SnappyScriptGenerator.class);

    private static Pattern BOOLEAN = Pattern.compile("true|false");

    private static Pattern NUMBER = Pattern.compile("[0-9]+\\.?[0-9]*");

    private static Map<String, Object> parseParameters(XMLStreamReader in) throws XMLStreamException {
        Map<String, Object> map = new HashMap<>();
        in.next();
        while (!(in.isEndElement() && in.getName().equals(new QName("parameters")))) {
            if (in.isStartElement()) {
                try {
                    String text = in.getElementText();
                    String name = in.getLocalName();
                    if (BOOLEAN.matcher(text).matches()) {
                        map.put(name, Boolean.parseBoolean(text));
                    } else if (NUMBER.matcher(text).matches()) {
                        map.put(name, Double.parseDouble(text));
                    } else {
                        map.put(name, text);
                    }
                } catch (Throwable e) {
                    LOG.info("Skipping element '{}' due to parsing error '{}'.", in.getLocalName(), e.getMessage());
                }
            }
            in.next();
        }
        return map;
    }

    private static void convertNode(XMLStreamReader in, PrintWriter out) throws XMLStreamException {
        String operator = null;
        Map<String, Object> map = null;
        while (!(in.isEndElement() && in.getName().equals(new QName("node")))) {
            if (in.isStartElement() && in.getName().equals(new QName("operator"))) {
                operator = in.getElementText();
            }
            if (in.isStartElement() && in.getName().equals(new QName("parameters"))) {
                map = parseParameters(in);
            }
            in.next();
        }
        if (operator != null) {
            generateNodeCode(operator, map, out);
        }
        out.flush();
    }

    private static void generateHeader(PrintWriter out) {
        out.println("from snappy import ProductIO, HashMap, GPF, jpy");
        out.println("import os, sys, gc");
        out.println("from osgeo.osr import SpatialReference");
        out.println();
        out.println("GPF.getDefaultInstance().getOperatorSpiRegistry().loadOperatorSpis()");
        out.println("HashMap = jpy.get_type('java.util.HashMap')");
        out.println();
        out.println("# generated operations follow");
    }

    private static void generateNodeCode(String operator, Map<String,Object> map, PrintWriter out) {
        if (operator.equals("Read")) {
            out.println("####################");
            out.println("# read data from file");
            out.println("####################");
            out.println("artifact = ProductIO.readProduct('" + map.get("file") + "')");
            out.println("print 'Done reading from file.'");
            out.println();
        } else if (operator.equals("Write")) {
            out.println("####################");
            out.println("# write result to file");
            out.println("####################");
            out.println("ProductIO.writeProduct(artifact, '" + map.get("file") + "', '" + map.get("formatName") + "')");
            out.println("print 'Done writing to file.'");
            out.println();
        } else {
            out.println("####################");
            out.println("# perform " + operator);
            out.println("####################");
            out.println("parameters = HashMap()");
            for (Map.Entry<String, Object> e : map.entrySet()) {
                Object value = e.getValue();
                if (value instanceof Boolean) {
                    out.println("parameters.put('" + e.getKey() + "', " + (((Boolean)value) ? "True" : "False") + ")");
                } else if (value instanceof Double) {
                    out.println("parameters.put('" + e.getKey() + "', " + value + ")");
                } else if (e.getKey().equals("mapProjection")) {
                    value = value.toString().replaceAll("\\n", " ");
                    out.println("srs = SpatialReference('" + value + "')");
                    out.println("srs.AutoIdentifyEPSG()");
                    out.println("code = srs.GetAuthorityName(None) + ':' + srs.GetAuthorityCode(None)");
                    out.println("parameters.put('mapProjection', code)");
                } else {
                    out.println("parameters.put('" + e.getKey() + "', '" + value + "')");
                }
            }
            out.println();
            out.println("# perform " + operator);
            out.println("artifact = GPF.createProduct('" + operator + "', parameters, artifact)");
            out.println("print '" + operator + " is done.'");
            out.println();
        }
    }

    public static void generate(InputStream xml, OutputStream py) throws XMLStreamException, UnsupportedEncodingException {
        XMLStreamReader in = XMLInputFactory.newInstance().createXMLStreamReader(xml);
        PrintWriter out = new PrintWriter(new OutputStreamWriter(py, "UTF-8"));
        generateHeader(out);
        while (moveReaderToFirstMatch(in, new QName("node"))) {
            convertNode(in, out);
        }
    }

    public static void main(String[] args) {
        InputStream in = null;
        OutputStream out = null;
        try {
            File xml = new File(args[0]);
            File py = new File(args[1]);
            generate(in = new FileInputStream(xml), out = new FileOutputStream(py));
        } catch (Throwable e) {
            LOG.error("Something went wrong during conversion: {}", e.getMessage());
            LOG.debug("Stack trace:", e);
        } finally {
            IOUtils.closeQuietly(in);
            IOUtils.closeQuietly(out);
        }
    }
}
