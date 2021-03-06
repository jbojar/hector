package me.prettyprint.cassandra.serializers;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import me.prettyprint.hector.api.Serializer;
import me.prettyprint.hector.api.exceptions.HectorSerializationException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PrefixedSerializer<P, S> extends AbstractSerializer<S> {

  private static Logger log = LoggerFactory.getLogger(PrefixedSerializer.class);

  P prefix;
  Serializer<P> prefixSerializer;
  ByteBuffer prefixBytes;
  Serializer<S> suffixSerializer;

  public PrefixedSerializer(P prefix, Serializer<P> prefixSerializer,
      Serializer<S> suffixSerializer) {
    this.prefix = prefix;
    this.prefixSerializer = prefixSerializer;
    this.suffixSerializer = suffixSerializer;

    prefixBytes = prefixSerializer.toByteBuffer(prefix);
    prefixBytes.rewind();
  }

  @Override
  public ByteBuffer toByteBuffer(S s) {
    if (s == null) {
      return null;
    }

    ByteBuffer sb = suffixSerializer.toByteBuffer(s);
    sb.rewind();

    ByteBuffer bb = ByteBuffer.allocate(prefixBytes.remaining()
        + sb.remaining());

    bb.put(prefixBytes.slice());
    bb.put(sb);

    bb.rewind();
    return bb;
  }

  @Override
  public S fromByteBuffer(ByteBuffer bytes) {
    if ((bytes == null) || !bytes.hasArray()) {
      return null;
    }

    bytes = bytes.duplicate();
    bytes.rewind();

    if (bytes.limit() < prefixBytes.remaining()) {
      log.error("Unprefixed value received, throwing exception...");
      throw new HectorSerializationException("Unexpected prefix value");
    }

    if (compareByteArrays(prefixBytes.array(), prefixBytes.arrayOffset()
        + prefixBytes.position(), prefixBytes.remaining(), bytes.array(),
        bytes.arrayOffset() + bytes.position(), prefixBytes.remaining()) != 0) {
      return null; // incorrect prefix, return nothing
    }
    bytes.position(prefixBytes.remaining());

    S s = suffixSerializer.fromByteBuffer(bytes);
    return s;
  }

  @Override
  public List<S> fromBytesList(List<ByteBuffer> list) {
    List<S> objList = new ArrayList<S>(list.size());
    for (ByteBuffer s : list) {
      try {
        ByteBuffer bb = s.slice();
        S fbb = fromByteBuffer(bb);
        if (fbb != null) {
          objList.add(fbb);
        }
      } catch (HectorSerializationException e) {
        // not a prefixed key, discard
      }
    }
    return objList;
  }

  @Override
  public <V> Map<S, V> fromBytesMap(Map<ByteBuffer, V> map) {
    Map<S, V> objMap = new LinkedHashMap<S, V>(
        computeInitialHashSize(map.size()));
    for (Entry<ByteBuffer, V> entry : map.entrySet()) {
      try {
        ByteBuffer bb = entry.getKey().slice();
        S fbb = fromByteBuffer(bb);
        if (fbb != null) {
          objMap.put(fbb, entry.getValue());
        }
      } catch (HectorSerializationException e) {
        // not a prefixed key, discard
      }
    }
    return objMap;
  }

  private static int compareByteArrays(byte[] bytes1, int offset1, int len1,
      byte[] bytes2, int offset2, int len2) {
    if (null == bytes1) {
      if (null == bytes2) {
        return 0;
      } else {
        return -1;
      }
    }
    if (null == bytes2) {
      return 1;
    }

    if (len1 < 0) {
      len1 = bytes1.length - offset1;
    }
    if (len2 < 0) {
      len2 = bytes2.length - offset2;
    }

    int minLength = Math.min(len1, len2);
    for (int i = 0; i < minLength; i++) {
      int i1 = offset1 + i;
      int i2 = offset2 + i;
      if (bytes1[i1] == bytes2[i2]) {
        continue;
      }
      // compare non-equal bytes as unsigned
      return (bytes1[i1] & 0xFF) < (bytes2[i2] & 0xFF) ? -1 : 1;
    }
    if (len1 == len2) {
      return 0;
    } else {
      return (len1 < len2) ? -1 : 1;
    }
  }

}
