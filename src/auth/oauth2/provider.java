package auth.oauth2;

import bcid.database;
import org.apache.commons.cli.*;
import util.stringGenerator;

import java.sql.*;
import java.util.Calendar;

/**
 * Created by rjewing on 2/15/14.
 */
public class provider {
    protected Connection conn;

    public provider() throws Exception {
        database db = new database();
        conn = db.getConn();
    }

    public Boolean validClientId(String clientId) {
        try {
            String selectString = "SELECT count(*) as count FROM oauthClients WHERE client_id = ?";
            PreparedStatement stmt = conn.prepareStatement(selectString);

            stmt.setString(1, clientId);

            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return rs.getInt("count") >= 1;
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return false;
    }

    public String getCallback(String clientID) throws SQLException {
        String selectString = "SELECT callback FROM oauthClients WHERE client_id = ?";
        PreparedStatement stmt = conn.prepareStatement(selectString);

        stmt.setString(1, clientID);

        ResultSet rs = stmt.executeQuery();
        if (rs.next()) {
            return rs.getString("callback");
        }
        return null;
    }

    public String generateCode(String clientID, String redirectURL, String username) throws Exception {
        stringGenerator sg = new stringGenerator();
        String code = sg.generateString(20);

        database db = new database();
        Integer user_id = db.getUserId(username);

        String insertString = "INSERT INTO oauthNonces (client_id, code, user_id, redirect_uri) VALUES(?, \"" + code + "\",?,?)";
        PreparedStatement stmt = conn.prepareStatement(insertString);

        stmt.setString(1, clientID);
        stmt.setInt(2, user_id);
        stmt.setString(3, redirectURL);

        stmt.execute();
        return code;
    }

    public String generateClientId() {
        stringGenerator sg = new stringGenerator();
        return sg.generateString(20);
    }

    public String generateClientSecret() {
        stringGenerator sg = new stringGenerator();
        return sg.generateString(75);
    }

    public Boolean validateClient(String clientId, String clientSecret) {
        try {
            String selectString = "SELECT count(*) as count FROM oauthClients WHERE client_id = ? AND client_secret = ?";
            PreparedStatement stmt = conn.prepareStatement(selectString);

            stmt.setString(1, clientId);
            stmt.setString(2, clientSecret);

            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return rs.getInt("count") >= 1;
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return false;
    }

    public Boolean validateCode(String clientID, String code, String redirectURL) {
        try {
            String selectString = "SELECT ts FROM oauthNonces WHERE client_id = ? AND code = ? AND redirect_uri = ?";
            PreparedStatement stmt = conn.prepareStatement(selectString);

            stmt.setString(1, clientID);
            stmt.setString(2, code);
            stmt.setString(3, redirectURL);

            ResultSet rs = stmt.executeQuery();

            if (rs.next()) {
                Timestamp ts = rs.getTimestamp("ts");
                // get a Timestamp instance for 10 mins ago
                Timestamp expiredTs = new Timestamp(Calendar.getInstance().getTime().getTime() - 600000);
                // if ts is older then 10 mins, we can't proceed
                if (ts == null || ts.before(expiredTs)) {
                    return false;
                }
                return true;
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return false;
    }

    private Integer getUserId(String clientId, String code) {
        Integer user_id = null;
        try {
            String selectString = "SELECT user_id FROM oauthNonces WHERE client_id=? AND code=?";
            PreparedStatement stmt = conn.prepareStatement(selectString);

            stmt.setString(1, clientId);
            stmt.setString(2, code);

            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                user_id = rs.getInt("user_id");
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
        return user_id;
    }

    private void deleteNonce(String clientId, String code) {
        try {
            // need to do this here instead of
            String deleteString = "DELETE FROM oauthNonces WHERE client_id = ? AND code = ?";
            PreparedStatement stmt2 = conn.prepareStatement(deleteString);

            stmt2.setString(1, clientId);
            stmt2.setString(2, code);

            stmt2.execute();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public String generateToken(String clientID, String state, String code) throws SQLException{
        stringGenerator sg = new stringGenerator();
        String token = sg.generateString(20);

        Integer user_id = getUserId(clientID, code);
        deleteNonce(clientID, code);
        if (user_id == null) {
            return "[{\"error\": \"server_error\"}]";
        }

        String insertString = "INSERT INTO oauthTokens (client_id, token, user_id) VALUE (?, \"" + token +"\", ?)";
        PreparedStatement stmt = conn.prepareStatement(insertString);

        stmt.setString(1, clientID);
        stmt.setInt(2, user_id);
        stmt.execute();

        StringBuilder sb = new StringBuilder();
        sb.append("[{");
        sb.append("\"access_token\":\"" + token + "\",\n");
        sb.append("\"token_type\":\"bearer\",\n");
        sb.append("\"expires_in\":3600\n");
        if (state != null) {
            sb.append(("\"state\":\"" + state + "\""));
        }
        sb.append("}]");

        return sb.toString();
    }

    /**
     * verify that a token is still valid
     * @param token the access_token issued to the client
     * @return user_id the token represents, null if invalid token
     */
    public Integer validateToken(String token) {
        try {
            String selectString = "SELECT ts, user_id FROM oauthTokens WHERE token=?";
            PreparedStatement stmt = conn.prepareStatement(selectString);

            stmt.setString(1, token);

            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {

                Timestamp ts = rs.getTimestamp("ts");
                // get a Timestamp instance for 1 hr ago
                Timestamp expiredTs = new Timestamp(Calendar.getInstance().getTime().getTime() - 3600000);
                // if ts is older then 1 hr, we can't proceed
                if (ts != null || ts.after(expiredTs)) {
                    return rs.getInt("user_id");
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        return null;
    }

    /**
     * given a hostname, register a client app for oauth use
     * @param args
     */
    public static void main(String args[]) {
        // Some classes to help us
        CommandLineParser clp = new GnuParser();
        CommandLine cl;

        Options options = new Options();
        options.addOption("c", "callback url", true, "The callback url of the client app");

        try {
            cl = clp.parse(options, args);
        } catch (UnrecognizedOptionException e) {
            System.out.println("Error: " + e.getMessage());
            return;
        } catch (ParseException e) {
            System.out.println("Error: " + e.getMessage());
            return;
        }

        if (!cl.hasOption("c")) {
            System.out.println("You must enter a callback url");
            return;
        }

        String host = cl.getOptionValue("c");
        try {
            provider p = new provider();

            String clientId = p.generateClientId();
            String clientSecret = p.generateClientSecret();

            String insertString = "INSERT INTO oauthClients (client_id, client_secret, callback) VALUES (\""
                                  + clientId + "\",\"" + clientSecret + "\",?)";
            PreparedStatement stmt = p.conn.prepareStatement(insertString);

            stmt.setString(1, host);
            stmt.execute();

            System.out.println("Successfully registered oauth2 client app at host: " + host
                    + ".\nYou will need the following information:\n\nclient_id: "
                    + clientId + "\nclientSecret: " + clientSecret);
        } catch (Exception e) {
            e.printStackTrace();
            return;
        }
    }
}
