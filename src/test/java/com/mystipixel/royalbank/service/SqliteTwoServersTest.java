package com.mystipixel.royalbank.service;

import org.bukkit.configuration.file.YamlConfiguration;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

// both servers open the same bank.db through their own pool of one connection
class SqliteTwoServersTest extends TwoServerScenarios {

    @Override
    void configureStorage(YamlConfiguration config) {
        config.set("storage.type", "SQLITE");
        config.set("storage.sqlite-file", "bank.db");
    }

    @Override
    Connection rawConnection() throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + dataFolder.resolve("bank.db"));
    }
}
