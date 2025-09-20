package de.schliweb.makeacopy.utils;

import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class WordsJsonTest {

    @Test
    public void parseSimpleArray() {
        String json = "[" +
                "{\"text\":\"Hello\",\"left\":10,\"top\":20,\"right\":50,\"bottom\":35,\"confidence\":0.9}," +
                "{\"text\":\"World\",\"bbox\":{\"l\":5,\"t\":6,\"r\":15,\"b\":16},\"confidence\":90}" +
                "]";
        List<RecognizedWord> words = WordsJson.parse(json);
        assertNotNull(words);
        assertEquals(2, words.size());
        assertEquals("Hello", words.get(0).getText());
        assertEquals("World", words.get(1).getText());
    }

    @Test
    public void parseFromFile() throws Exception {
        File tmp = File.createTempFile("words", ".json");
        try (FileOutputStream fos = new FileOutputStream(tmp)) {
            fos.write("[{\"text\":\"X\",\"left\":1,\"top\":2,\"right\":3,\"bottom\":4,\"confidence\":1}]".getBytes(StandardCharsets.UTF_8));
        }
        List<RecognizedWord> words = WordsJson.parseFile(tmp);
        assertNotNull(words);
        assertEquals(1, words.size());
        assertEquals("X", words.get(0).getText());
        // cleanup
        //noinspection ResultOfMethodCallIgnored
        tmp.delete();
    }

    @Test
    public void parseRootWrapperVariants() {
        String jsonWords = "{\"words\":[{\"text\":\"A\",\"left\":1,\"top\":2,\"right\":3,\"bottom\":4}]}";
        String jsonData = "{\"data\":[{\"text\":\"B\",\"bbox\":[0,0,10,10]}]}";
        assertEquals("A", WordsJson.parse(jsonWords).get(0).getText());
        assertEquals("B", WordsJson.parse(jsonData).get(0).getText());
    }

    @Test
    public void parseBboxArrayLTRBandXYWH() {
        String json = "[" +
                // [l,t,r,b]
                "{\"text\":\"LTRB\",\"bbox\":[10,20,50,60]}," +
                // [x,y,w,h]
                "{\"text\":\"XYWH\",\"bbox\":[10,20,40,40]}" +
                "]";
        List<RecognizedWord> words = WordsJson.parse(json);
        assertEquals(2, words.size());
        assertEquals("LTRB", words.get(0).getText());
        assertEquals("XYWH", words.get(1).getText());
    }

    @Test
    public void parseFlatXYWHAndXminXmax() {
        String json = "[" +
                "{\"text\":\"XYWH\",\"x\":5,\"y\":6,\"w\":10,\"h\":20}," +
                "{\"text\":\"MINMAX\",\"xmin\":1,\"ymin\":2,\"xmax\":11,\"ymax\":22}" +
                "]";
        List<RecognizedWord> words = WordsJson.parse(json);
        assertEquals(2, words.size());
        assertEquals("XYWH", words.get(0).getText());
        assertEquals("MINMAX", words.get(1).getText());
    }

    @Test
    public void parseStringNumbersAndConfidence() {
        String json = "[" +
                "{\"text\":\"S\",\"left\":\"1\",\"top\":\"2\",\"right\":\"3\",\"bottom\":\"4\",\"confidence\":\"95\"}" +
                "]";
        List<RecognizedWord> words = WordsJson.parse(json);
        assertEquals(1, words.size());
        assertEquals("S", words.get(0).getText());
        // confidence 95% should map to 0.95 in PdfCreator downstream; here we just ensure it parses without error
    }

    @Test
    public void skipsMalformedEntries() {
        String json = "[" +
                // missing bbox entirely
                "{\"text\":\"X\"}," +
                // non-numeric bbox values
                "{\"text\":\"Y\",\"left\":\"a\",\"top\":\"b\",\"right\":\"c\",\"bottom\":\"d\"}," +
                // bbox array too short
                "{\"text\":\"Z\",\"bbox\":[1,2,3]}" +
                "]";
        List<RecognizedWord> words = WordsJson.parse(json);
        assertNotNull(words);
        assertEquals(0, words.size());
    }
}
