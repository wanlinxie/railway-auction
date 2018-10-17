package railway.sim;

import java.awt.Desktop;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.List;
import java.util.Scanner;
import java.util.Set;
// import java.util.stream.Collectors;

import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

import railway.sim.utils.*;

class LinkInfo {
    public int town1;
    public int town2;

    public double distance;
}

public class Simulator {
    private static final String root = "railway";
    private static final String statics_root = "statics";

    private static boolean gui = false;
    private static long timeout = 1000;
    private static double fps = 1;

    // Files - hardcoded.
    private static String geo_f = "geography";
    private static String transit_f = "transit";
    private static String infra_f = "infrastructure";
    private static String dir = "railway/sim/input/";

    private static List<Coordinates> geo = new ArrayList<>();
    private static List<List<Integer>> infra = new ArrayList<>();
    private static int[][] transit;
    private static Map<String, Integer> townRevLookup = new HashMap<>();
    private static List<String> townLookup = new ArrayList<>();
    private static List<LinkInfo> links = new ArrayList<>();

    private static List<String> playerNames;
    private static List<PlayerWrapper> players;
    private static List<PlayerWrapper> origPlayers;

    private static List<BidInfo> allBids = new ArrayList<>();

    private static int uniq = 1;

    public static void main(String[] args) throws ClassNotFoundException, InstantiationException, IllegalAccessException, IOException {
        parseArgs(args);
        loadInputFiles();

        players = new ArrayList<>();
        try {
            for (String name : playerNames) {
                players.add(loadPlayerWrapper(name, timeout));
            }
        } catch (Exception ex) {
            System.out.println("Unable to load players. " + ex.getMessage());
            System.exit(0);
        }

        origPlayers = new ArrayList<>(players);

        HTTPServer server = null;
        if (gui) {
            server = new HTTPServer();
            Log.record("Hosting HTTP Server on " + server.addr());
            if (!Desktop.isDesktopSupported())
                Log.record("Desktop operations not supported");
            else if (!Desktop.getDesktop().isSupported(Desktop.Action.BROWSE))
                Log.record("Desktop browse operation not supported");
            else {
                try {
                    Desktop.getDesktop().browse(new URI("http://localhost:" + server.port()));
                } catch (URISyntaxException e) {
                    e.printStackTrace();
                }
            }
        }

        initBids();
        double budget = getBudget();
        List<PlayerWrapper> updates = new ArrayList<>(players);
        for (PlayerWrapper pw : players) {
            try {
                pw.init(budget, deepClone(geo), deepClone(infra), 
                    deepClone(transit), deepClone(townLookup));
            }
            catch(Exception ex) {
                System.out.println("Exception in initializing player " +
                    pw.getName() + " " + ex.getMessage());
                updates.remove(pw);
            }
        }

        // Update the list of players to remove those that threw exceptions.
        players = updates;

        if (gui) {
            gui(server, state(fps, geo, infra, budget, origPlayers));
        }

        boolean isComplete = false;
        int round = 1;
        try {
            while (!isComplete) {
                System.out.println("\nRound " + round++);

                Collections.shuffle(players);
                updates = new ArrayList<>(players);

                int nullBids = 0;
                Bid maxBid = null;
                PlayerWrapper maxBidPlayer = null;
                for (PlayerWrapper pw : players) {
                    Bid bid = null;

                    try {
                        bid = pw.getBid(deepClone(allBids));
                    }
                    catch (Exception ex) {
                        System.out.println("Exception in getting bid for player " +
                            pw.getName() + " " + ex.toString());
                        updates.remove(pw);
                    }

                    if (bid == null) {
                        ++nullBids;
                    }
                    else if (isMaxBid(bid, maxBid)) {
                        maxBid = bid;
                        maxBidPlayer = pw;
                    }
                }

                if (nullBids == players.size()) {
                    isComplete = true;
                }
                else {
                    updateBids(maxBid, maxBidPlayer.getName(), allBids);
                    maxBidPlayer.updateBudget(maxBid.amount);

                    System.out.println("Max bid: " + maxBidPlayer.getName());
                }

                if (allLinksTaken(allBids)) {
                    isComplete = true;
                }

                if (gui) {
                    gui(server, state(isComplete ? -1 : fps, allBids, origPlayers));
                }
            }
        } catch (Exception ex) {
            System.out.println("Exception! ");
            ex.printStackTrace();
            System.exit(0);
        }

        double[][] revenue = getRevenue();
        printPrev(revenue);

        printStats();

        System.exit(0);
    }

    public static void printStats() {                                                                  
        System.out.println("\n******** Results ********");
    }

    public static void printRev(double[][] rev) {
        for (int i=0; i<rev.length; ++i) {
            for (int j=0; j<rev[i].length; ++j) {
                System.out.print(rev[i][j] + " ");
            }

            System.out.println();
        }
    }

    private static double[][] getRevenue() {
        final int n = geo.size();
        WeightedGraph g = new WeightedGraph(n);

        for (int i=0; i<n; ++i) {
            g.setLabel(townLookup.get(i));
        }

        for (int i=0; i<infra.size(); ++i) {
            for (int j=0; j<infra.get(i).size(); ++j) {
                g.addEdge(i, infra.get(i).get(j), getDistance(i, infra.get(i).get(j)));
            }
        }

        //g.print();

        double[][] revenue = new double[n][n];

        for (int i=0; i<n; ++i) {
            int[][] prev = Dijkstra.dijkstra(g, i);
            for (int j=i+1; j<n; ++j) {
                List<List<Integer>> allPaths = Dijkstra.getPaths(g, prev, j);

                double cost = 0;
                for (int k=0; k<allPaths.get(0).size()-1; ++k) {
                    cost += getDistance(allPaths.get(0).get(k), allPaths.get(0).get(k+1));
                }

                revenue[i][j] = cost * transit[i][j] * 10;
            }
        }

        return revenue;
    }

    private static boolean allLinksTaken(List<BidInfo> allBids) {
        for (BidInfo bi : allBids) {
            if (bi.owner == null) {
                return false;
            }
        }

        return true;
    }

    private static void updateBids(Bid bid, String player, List<BidInfo> allBids) {
        BidInfo bi = allBids.get(bid.id1);
        double amount = bid.amount;

        if (bid.id2 != -1) {
            BidInfo bi2 = allBids.get(bid.id2);
            bi2.amount = amount/2;
            bi2.owner = player;
            amount /= 2;
        }

        bi.amount = amount;
        bi.owner = player;
    }

    private static boolean isMaxBid(Bid bid, Bid maxBid) {
        // Can optimize further.
        if (maxBid == null) {
            return true;
        }

        return bid.amount/getDistance(bid) > maxBid.amount/getDistance(maxBid);
    }

    private static double getDistance(Bid bid) {
        double dist = getDistance(bid.id1);

        if (bid.id2 != -1) {
            dist += getDistance(bid.id2);
        }

        return dist;
    }

    private static double getDistance(int linkId) {
        LinkInfo li = links.get(linkId);
        return getDistance(li.town1, li.town2);
    }

    private static double getDistance(int t1, int t2) {
        return Math.pow(
            Math.pow(geo.get(t1).x - geo.get(t2).x, 2) + 
                Math.pow(geo.get(t1).y - geo.get(t2).y, 2), 
            0.5);
    }

    private static void initBids() {
        int id = 0;

        for (int i=0; i < infra.size(); ++i) {
            for (int j=0; j < infra.get(i).size(); ++j) {
                BidInfo bi = new BidInfo();
                bi.id = id;

                int t1 = i;
                int t2 = infra.get(i).get(j);
                bi.town1 = townLookup.get(t1);
                bi.town2 = townLookup.get(t2);

                LinkInfo li = new LinkInfo();
                li.town1 = t1;
                li.town2 = t2;
                li.distance = getDistance(t1, t2);
                links.add(li);

                bi.amount = transit[t1][t2] * li.distance * 10;

                List<BidInfo> dups =
                    getDuplicateLinks(bi.town1, bi.town2);
                if (dups != null && dups.size() > 0) {
                    int c_size = dups.size();
                    int new_size = c_size + 1;

                    for (BidInfo d : dups) {
                        d.amount = d.amount * c_size / new_size;
                    }

                    bi.amount /= new_size;
                }

                allBids.add(bi);
                id += 1;
            }
        }
    }

    private static List<BidInfo> getDuplicateLinks(String t1, String t2) {
        List<BidInfo> dups = new ArrayList<>();
        for (BidInfo a : allBids) {
            if (a.town1.equals(t1) && a.town2.equals(t2)) {
                dups.add(a);
            }
        }

        return dups;
    }

    private static double getBudget() {
        int g = players.size();

        double totalAmount = 0;
        for (BidInfo bi : allBids) {
            totalAmount += bi.amount;
        }

        totalAmount *= 2;

        return (int)totalAmount/g;
    }

    private static void loadInputFiles() {
        // Process geo.

        try {
            String path = dir + geo_f;
            File file = new File(path);
            Scanner sc = new Scanner(file);
            int index = 0;

            townLookup = new ArrayList<>();
            while (sc.hasNextLine()) {
                String line = sc.nextLine();
                String[] res = line.split(",");

                townRevLookup.put(res[0], index);
                townLookup.add(res[0]);
                geo.add(new Coordinates(Integer.parseInt(res[1]), Integer.parseInt(res[2])));
                infra.add(new ArrayList<Integer>());
                index += 1;
            }

            // Process infrastructure.
            path = dir + infra_f;
            file = new File(path);
            sc = new Scanner(file);
            while (sc.hasNextLine()) {
                String line = sc.nextLine();
                String[] res = line.split(",");
                infra.get(townRevLookup.get(res[0])).add(townRevLookup.get(res[1]));
                // infra.get(townRevLookup.get(res[1])).add(res[0]);
            }

            transit = new int[index][index];

            // Process transit.
            // How to store??
            path = dir + transit_f;
            file = new File(path);
            sc = new Scanner(file);
            while (sc.hasNextLine()) {
                String line = sc.nextLine();
                String[] res = line.split(",");
                transit[townRevLookup.get(res[0])][townRevLookup.get(res[1])] = 
                    Integer.parseInt(res[2]);
                // transit[townRevLookup.get(res[1])][townRevLookup.get(res[0])] = 
                //    Integer.parseInt(res[2]);
            }
        }
        catch (Exception ex) {
            System.out.println("Exception! " + ex.getMessage());
        }
    }

    private static List<List<Integer>> getClone(List<List<Integer>> lol) {
        List<List<Integer>> newLol = new ArrayList<>();
        for (List<Integer> l : lol) {
            newLol.add(new ArrayList<Integer>(l));
        }

        return newLol;
    }

    private static <T extends Object> T deepClone(T object) {
        try {
            ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
            ObjectOutputStream objectOutputStream = new ObjectOutputStream(byteArrayOutputStream);
            objectOutputStream.writeObject(object);
            ByteArrayInputStream bais = new ByteArrayInputStream(byteArrayOutputStream.toByteArray());
            ObjectInputStream objectInputStream = new ObjectInputStream(bais);
            return (T) objectInputStream.readObject();
        }
        catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private static int[] getScores() {
        throw new UnsupportedOperationException();
    }

    private static PlayerWrapper loadPlayerWrapper(String name, long timeout) throws Exception {
        String p_name = name.split("-")[0];

        Log.record("Loading player " + name);
        Player p = loadPlayer(p_name);
        if (p == null) {
            Log.record("Cannot load player " + name);
            System.exit(1);
        }

        return new PlayerWrapper(p, name, timeout);
    }

    // The state that is sent to the GUI. (JSON)
    private static String state(
        double fps,
        List<Coordinates> geo,
        List<List<Integer>> infra,
        double budget,
        List<PlayerWrapper> players) {

        // Here we are again.
        String json = "{\"refresh\":\"" + (1000.0/fps) + "\",\"budget\":\"" + budget +
            "\",\"geo\":\"";

        for (int i=0; i<townLookup.size(); ++i) {
            json += townLookup.get(i) + "," + geo.get(i).x + "," + geo.get(i).y + ";";
        }

        // Remove the ";" at the end.
        json = json.substring(0, json.length() - 1);
        json += "\",\"infra\":\"";

        for (int i=0; i<townLookup.size(); ++i) {
            for (int j=0; j<infra.get(i).size(); ++j) {
                json += townLookup.get(i) + "," + townLookup.get(infra.get(i).get(j)) + ";";
            }
        }

        json = json.substring(0, json.length() - 1);
        json += "\",\"players\":\"" + String.join(",", playerNames) + "\"}";

        // System.out.println(json);

        return json;
    }

    // The state that is sent to the GUI. (JSON)
    private static String state(
        double fps,
        List<BidInfo> allBids,
        List<PlayerWrapper> players) {

        String json = "{\"refresh\":\"" + (1000.0/fps) + "\",\"owners\":\"";

        for (BidInfo bi : allBids) {
            if (bi.owner != null) {
                json += bi.town1 + "," + bi.town2 + "," + bi.owner + ";";
            }
            else {
                json += bi.town1 + "," + bi.town2 + ",None;";
            }
        }

        json = json.substring(0, json.length() - 1);
        json += "\",\"budget\":\"";

        for (PlayerWrapper pw : players) {
            json += pw.getName() + "," + pw.getBudget() + ";";
        }

        // Remove the ";" at the end.
        json = json.substring(0, json.length() - 1);
        json += "\"}";

        // System.out.println(json);

        return json;
    }

    private static void gui(HTTPServer server, String content) {
        if (server == null) return;
        String path = null;
        for (;;) {
            for (;;) {
                try {
                    path = server.request();
                    break;
                } catch (IOException e) {
                    Log.record("HTTP request error " + e.getMessage());
                }
            }
            if (path.equals("data.txt")) {
                try {
                    server.reply(content);
                } catch (IOException e) {
                    Log.record("HTTP dynamic reply error " + e.getMessage());
                }
                return;
            }
            if (path.equals("")) path = "webpage.html";
            else if (!Character.isLetter(path.charAt(0))) {
                Log.record("Potentially malicious HTTP request \"" + path + "\"");
                break;
            }

            File file = new File(statics_root + File.separator + path);
            if (file == null) {
                Log.record("Unknown HTTP request \"" + path + "\"");
            } else {
                try {
                    server.reply(file);
                } catch (IOException e) {
                    Log.record("HTTP static reply error " + e.getMessage());
                }
            }
        }
    }

    private static void parseArgs(String[] args) {
        int i = 0;
        playerNames = new ArrayList<String>();
        for (; i < args.length; ++i) {
            switch (args[i].charAt(0)) {
                case '-':
                    if (args[i].equals("-p") || args[i].equals("--players")) {
                        while (i + 1 < args.length && args[i + 1].charAt(0) != '-') {
                            ++i;
                            if (playerNames.contains(args[i])) {
                                playerNames.add(args[i] + "-" + uniq++);
                            }
                            else {
                                playerNames.add(args[i]);
                            }
                        }
                    } else if (args[i].equals("-g") || args[i].equals("--gui")) {
                        gui = true;
                    } else if (args[i].equals("-l") || args[i].equals("--logfile")) {
                        if (++i == args.length) {
                            throw new IllegalArgumentException("Missing logfile name");
                        }
                        Log.setLogFile(args[i]);
                    } else if (args[i].equals("-t") || args[i].equals("--timeout")) {
                        if (++i == args.length) {
                            throw new IllegalArgumentException("Missing timeout value.");
                        }

                        timeout = Integer.parseInt(args[i]) * 1000;
                    } else if (args[i].equals("--fps")) {
                        if (++i == args.length) {
                            throw new IllegalArgumentException("Missing frames per second.");
                        }
                        fps = Double.parseDouble(args[i]);
                    } else if (args[i].equals("-v") || args[i].equals("--verbose")) {
                        Log.activate();
                    } else {
                        throw new IllegalArgumentException("Unknown argument '" + args[i] + "'");
                    }
                    break;
                default:
                    throw new IllegalArgumentException("Unknown argument '" + args[i] + "'");
            }
        }

        if (playerNames.size() == 0) {
            // Set all groups by default.
            playerNames = new ArrayList<>(
                Arrays.asList(new String[] {"g1", "g2", "g3", "g4", "g5", "g6", "g7", "g8"}));
        }

        Log.record("Players: " + playerNames.toString());
        Log.record("GUI " + (gui ? "enabled" : "disabled"));

        if (gui)
            Log.record("FPS: " + fps);
    }

    private static Set<File> directory(String path, String extension) {
        Set<File> files = new HashSet<File>();
        Set<File> prev_dirs = new HashSet<File>();
        prev_dirs.add(new File(path));
        do {
            Set<File> next_dirs = new HashSet<File>();
            for (File dir : prev_dirs)
                for (File file : dir.listFiles())
                    if (!file.canRead()) ;
                    else if (file.isDirectory())
                        next_dirs.add(file);
                    else if (file.getPath().endsWith(extension))
                        files.add(file);
            prev_dirs = next_dirs;
        } while (!prev_dirs.isEmpty());
        return files;
    }

    public static Player loadPlayer(String name) throws IOException, ClassNotFoundException, InstantiationException, IllegalAccessException {
        String sep = File.separator;
        Set<File> player_files = directory(root + sep + name, ".java");
        File class_file = new File(root + sep + name + sep + "Player.class");
        long class_modified = class_file.exists() ? class_file.lastModified() : -1;
        if (class_modified < 0 || class_modified < last_modified(player_files) ||
                class_modified < last_modified(directory(root + sep + "sim", ".java"))) {
            JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
            if (compiler == null)
                throw new IOException("Cannot find Java compiler");
            StandardJavaFileManager manager = compiler.
                    getStandardFileManager(null, null, null);
//            long files = player_files.size();
            Log.record("Compiling for player " + name);
            if (!compiler.getTask(null, manager, null, null, null,
                    manager.getJavaFileObjectsFromFiles(player_files)).call())
                throw new IOException("Compilation failed");
            class_file = new File(root + sep + name + sep + "Player.class");
            if (!class_file.exists())
                throw new FileNotFoundException("Missing class file");
        }
        ClassLoader loader = Simulator.class.getClassLoader();
        if (loader == null)
            throw new IOException("Cannot find Java class loader");
        @SuppressWarnings("rawtypes")
        Class raw_class = loader.loadClass(root + "." + name + ".Player");
        return (Player)raw_class.newInstance();
    }

    private static long last_modified(Iterable<File> files) {
        long last_date = 0;
        for (File file : files) {
            long date = file.lastModified();
            if (last_date < date)
                last_date = date;
        }
        return last_date;
    }
}