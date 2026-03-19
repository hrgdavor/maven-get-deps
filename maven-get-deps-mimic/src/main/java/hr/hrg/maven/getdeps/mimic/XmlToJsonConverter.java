package hr.hrg.maven.getdeps.mimic;

import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import java.io.InputStream;
import java.util.*;

public class XmlToJsonConverter {

    public static Map<String, Object> convert(InputStream is) throws Exception {
        SAXParserFactory factory = SAXParserFactory.newInstance();
        SAXParser saxParser = factory.newSAXParser();
        Handler handler = new Handler();
        saxParser.parse(is, handler);
        Map<String, Object> result = handler.getResult();
        if (result != null) {
            return (Map<String, Object>) unwrap(result);
        }
        return null;
    }

    private static Object unwrap(Object obj) {
        if (obj instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) obj;
            if (map.size() == 1 && map.containsKey("_text")) {
                return map.get("_text");
            }
            Map<String, Object> newMap = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : map.entrySet()) {
                newMap.put(entry.getKey(), unwrap(entry.getValue()));
            }
            return newMap;
        } else if (obj instanceof List) {
            List<Object> list = (List<Object>) obj;
            List<Object> newList = new ArrayList<>(list.size());
            for (Object item : list) {
                newList.add(unwrap(item));
            }
            return newList;
        }
        return obj;
    }

    private static class Handler extends DefaultHandler {
        private final Stack<Map<String, Object>> stack = new Stack<>();
        private Map<String, Object> result;
        private final StringBuilder content = new StringBuilder();

        public Map<String, Object> getResult() {
            return result;
        }

        @Override
        public void startElement(String uri, String localName, String qName, Attributes attributes) throws SAXException {
            Map<String, Object> element = new LinkedHashMap<>();
            String name = (qName == null || qName.isEmpty()) ? localName : qName;
            
            if (stack.isEmpty()) {
                result = element;
            } else {
                Map<String, Object> parent = stack.peek();
                Object existing = parent.get(name);
                if (existing == null) {
                    parent.put(name, element);
                } else if (existing instanceof List) {
                    ((List<Object>) existing).add(element);
                } else {
                    List<Object> list = new ArrayList<>();
                    list.add(existing);
                    list.add(element);
                    parent.put(name, list);
                }
            }
            stack.push(element);
            content.setLength(0);
        }

        @Override
        public void endElement(String uri, String localName, String qName) throws SAXException {
            Map<String, Object> element = stack.pop();
            String text = content.toString().trim();
            if (!text.isEmpty()) {
                element.put("_text", text);
            }
            content.setLength(0);
        }

        @Override
        public void characters(char[] ch, int start, int length) throws SAXException {
            content.append(ch, start, length);
        }
    }
}
