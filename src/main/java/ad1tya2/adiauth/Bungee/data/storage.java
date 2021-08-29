package ad1tya2.adiauth.Bungee.data;

import ad1tya2.adiauth.Bungee.AdiAuth;
import ad1tya2.adiauth.Bungee.Config;
import ad1tya2.adiauth.Bungee.utils.Uuids;
import ad1tya2.adiauth.Bungee.utils.tools;
import ad1tya2.adiauth.Bungee.UserProfile;
import com.google.common.base.Charsets;
import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;

import java.io.IOException;
import java.sql.*;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

import static ad1tya2.adiauth.Bungee.utils.Uuids.*;

public class storage {
    private static ConcurrentHashMap<String, UserProfile> pMap = new ConcurrentHashMap<String, UserProfile>();
    private static ConcurrentHashMap<UUID, UserProfile> pMapByPremiumUuid = new ConcurrentHashMap<UUID, UserProfile>();

    public static void load(){
        try {
            mysql.load();
            tools.log("&eLoading Players...");
            Connection conn = mysql.getConnection();
            Statement stmt = conn.createStatement();
            int users = 0;
            stmt.execute("CREATE TABLE IF NOT EXISTS auth_users( uuid CHAR(36) PRIMARY KEY, lastIp VARCHAR(40), password VARCHAR(256), username VARCHAR(16), " +
                    "premiumUuid CHAR(36))");
            ResultSet records = stmt.executeQuery("SELECT uuid, lastIp, password, username, premiumUuid FROM auth_users");
            while (records.next()){
                users++;
                UserProfile user = new UserProfile();
                user.uuid = UUID.fromString(records.getString(1));
                user.lastIp = records.getString(2);
                user.password = records.getString(3);
                user.username = records.getString(4);
                try {
                    user.premiumUuid = UUID.fromString(records.getString(5));
                } catch (Exception e){
                    user.premiumUuid = null;
                }
                addPlayerDirect(user);
            }
            records.close();
            stmt.close();
            tools.log("&bLoad complete!, loaded &2"+users+" &bUsers");
        } catch (Exception e){
            e.printStackTrace();
        }
    }

    private static void logApiError(){
        tools.log(Level.SEVERE, "&cMojang api and/or Backup server is not responding, Please check, &eWill login the player using the last updated data");
    }

    public static UserProfile getPlayerForLogin(String name, String ip){
                UserProfile user = new UserProfile();
                user.username = name;
                user.lastIp = ip;
                UserProfile oldUserByName = pMap.get(name);
                Optional<UUID> uuid;
                if(Config.forceBackupServer){
                    uuid = Uuids.getBackupServerUUID(name);
                }
                else {
                    uuid = Uuids.getMojangUUid(name);
                    if (uuid == null) {
                        if (Config.backupServerEnabled) {
                            uuid = getBackupServerUUID(name);
                            tools.log(Level.WARNING, "&eUsing backup server for " + name);
                        }
                    }
                }
                if(uuid == null){
                    logApiError();
                    return oldUserByName;
                }

                    //Premium
                    if(uuid.isPresent()) {
                        user.premiumUuid = uuid.get();
                        user.uuid = user.premiumUuid;
                        UserProfile oldPremiumUser = pMapByPremiumUuid.get(user.premiumUuid);
                        if (oldPremiumUser == null) {
                            if (oldUserByName != null && !(oldUserByName.isPremium())) {
                                oldUserByName.lastIp = ip;
                                if (Config.convertOldCrackedToPremium) {
                                    oldUserByName.premiumUuid = user.premiumUuid;
                                }
                                updatePlayer(oldUserByName);
                                return oldUserByName;
                            }
                            updatePlayer(user);
                            return user;
                        } else if (oldPremiumUser.username != user.username) {
                            //Username change event
                            oldPremiumUser.username = user.username;
                            oldPremiumUser.lastIp = ip;
                            updatePlayer(oldPremiumUser);
                            return oldPremiumUser;
                        } else {
                            if (oldPremiumUser.lastIp != ip) {
                                oldPremiumUser.lastIp = ip;
                                updatePlayer(oldPremiumUser);
                            }
                            return oldPremiumUser;
                        }
                    }

                    //Cracked
                    else {
                        user.uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(Charsets.UTF_8));
                        user.premiumUuid = null;

                        if (oldUserByName == null) {
                            updatePlayer(user);

                            return user;
                        }

                        //If the username of this player has been converted to cracked from premium
                        //i.e If someone had a premium account with this name in the past and has changed his id to something else
                        try {
                            if (oldUserByName.isPremium() && oldUserByName.uuid != user.uuid) {
                                CloseableHttpClient client = HttpClients.createDefault();
                                HttpGet reqx = new HttpGet("https://api.mojang.com/user/profiles/" + getUndashedUuid(oldUserByName.premiumUuid) + "/names");
                                CloseableHttpResponse response = client.execute(reqx);
                                JsonParser parser = new JsonParser();
                                JsonArray rawUsernames = parser.parse(tools.getString(response.getEntity().getContent())).getAsJsonArray();
                                oldUserByName.username = rawUsernames.get(rawUsernames.size() - 1).getAsJsonObject().get("name").getAsString();
                                updatePlayer(oldUserByName);
                                updatePlayer(user);
                                response.close();
                                client.close();
                            }
                        }catch (Exception ignored){}


                        if (!oldUserByName.lastIp.equals(user.lastIp)) {
                            user.endSession();
                        }
                        return user;
                    }
    }

    public static UserProfile getPlayerDirect(String name){
        return pMap.get(name);
    }


    public static void asyncUserProfileUpdate(UserProfile profile){
        AdiAuth.instance.getProxy().getScheduler().runAsync(AdiAuth.instance, new Runnable() {
            @Override
            public void run() {
                try {
                    Connection conn = mysql.getConnection();
                    PreparedStatement stmt = conn.prepareStatement(
                            "REPLACE INTO auth_users(uuid, lastIp, password, username, premiumUuid) VALUES(?, ?, ?, ?, ?) ");
                    stmt.setString(1, profile.uuid.toString());
                    stmt.setString(2, profile.lastIp);
                    stmt.setString(3, profile.password);
                    stmt.setString(4, profile.username);
                    stmt.setString(5, profile.premiumUuid == null? null: profile.premiumUuid.toString());
                    stmt.executeUpdate();
                    stmt.close();

                } catch (SQLException e) {
                    e.printStackTrace();
                }
            }
        });
    }

    public static void updatePlayer(UserProfile player){
        addPlayerDirect(player);
        asyncUserProfileUpdate(player);
    }

    private static void addPlayerDirect(UserProfile profile){
        pMap.put(profile.username, profile);
        if(profile.premiumUuid != null) {
            pMapByPremiumUuid.put(profile.premiumUuid, profile);
        }
    }


}
