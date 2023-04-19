package com.github.wjw.realtimeauctions;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.msgpack.jackson.dataformat.MessagePackFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

public class MsgPackService {
  private static final ObjectMapper objectMapper = new ObjectMapper(new MessagePackFactory());

  /**
   * Decode from MagPack Base64 String.
   *
   * @param src the MagPack Base64 String
   * @return the io.vertx.core.json. json object
   */
  @SuppressWarnings("unchecked")
  public static io.vertx.core.json.JsonObject decodeFromMagPack(String src) {
    try {
      byte[] bb = Base64.getDecoder().decode(src.getBytes(StandardCharsets.UTF_8));

      java.util.Map<String, Object> mmp = objectMapper.readValue(bb, java.util.Map.class);

      io.vertx.core.json.JsonObject jsonObj = new io.vertx.core.json.JsonObject(mmp);

      return jsonObj;
    } catch (Exception e) {
      e.printStackTrace();
      return null;
    }
  }

  /**
   * Encode JsonObject to MagPack Base64 String.
   *
   * @param jsonObj the JsonObject
   * @return the MagPack Base64 String
   */
  public static String encodeToMagPack(io.vertx.core.json.JsonObject jsonObj) {
    try {
      byte[] bb     = objectMapper.writeValueAsBytes(jsonObj.getMap());
      String strObj = new String(Base64.getEncoder().encode(bb), StandardCharsets.UTF_8);

      return strObj;
    } catch (Exception e) {
      e.printStackTrace();
      return null;
    }
  }
}
