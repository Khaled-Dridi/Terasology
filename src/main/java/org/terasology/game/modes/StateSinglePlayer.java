/*
 * Copyright 2011 Benjamin Glatzel <benjamin.glatzel@me.com>.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.terasology.game.modes;

import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.Display;
import org.terasology.asset.AssetType;
import org.terasology.asset.AssetUri;
import org.terasology.componentSystem.UpdateSubscriberSystem;
import org.terasology.componentSystem.controllers.LocalPlayerSystem;
import org.terasology.components.LocalPlayerComponent;
import org.terasology.components.world.LocationComponent;
import org.terasology.entityFactory.PlayerFactory;
import org.terasology.entitySystem.ComponentSystem;
import org.terasology.entitySystem.EntityRef;
import org.terasology.entitySystem.EventSystem;
import org.terasology.entitySystem.PersistableEntityManager;
import org.terasology.entitySystem.persistence.EntityDataJSONFormat;
import org.terasology.entitySystem.persistence.EntityPersisterHelper;
import org.terasology.entitySystem.persistence.EntityPersisterHelperImpl;
import org.terasology.entitySystem.persistence.WorldPersister;
import org.terasology.events.input.*;
import org.terasology.events.input.binds.InventoryButton;
import org.terasology.game.ComponentSystemManager;
import org.terasology.game.CoreRegistry;
import org.terasology.game.GameEngine;
import org.terasology.game.Timer;
import org.terasology.game.bootstrap.EntitySystemBuilder;
import org.terasology.input.BindButtonEvent;
import org.terasology.input.CameraTargetSystem;
import org.terasology.input.InputSystem;
import org.terasology.logic.LocalPlayer;
import org.terasology.logic.manager.AssetManager;
import org.terasology.logic.manager.GUIManager;
import org.terasology.logic.manager.PathManager;
import org.terasology.logic.mod.Mod;
import org.terasology.logic.mod.ModManager;
import org.terasology.logic.world.Chunk;
import org.terasology.logic.world.WorldProvider;
import org.terasology.math.Vector3i;
import org.terasology.model.blocks.management.BlockManager;
import org.terasology.performanceMonitor.PerformanceMonitor;
import org.terasology.protobuf.EntityData;
import org.terasology.rendering.cameras.Camera;
import org.terasology.rendering.gui.menus.UILoadingScreen;
import org.terasology.rendering.gui.menus.UIStatusScreen;
import org.terasology.rendering.physics.BulletPhysicsRenderer;
import org.terasology.rendering.world.WorldRenderer;
import org.terasology.utilities.FastRandom;

import javax.vecmath.Vector3f;
import java.io.*;
import java.util.Iterator;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.lwjgl.opengl.GL11.*;

/**
 * Play mode.
 *
 * @author Benjamin Glatzel <benjamin.glatzel@me.com>
 * @author Anton Kireev <adeon.k87@gmail.com>
 * @version 0.1
 */
public class StateSinglePlayer implements GameState {

    public static final String ENTITY_DATA_FILE = "entity.dat";
    private Logger logger = Logger.getLogger(getClass().getName());

    private String currentWorldName;
    private String currentWorldSeed;

    private PersistableEntityManager entityManager;

    /* RENDERING */
    private WorldRenderer worldRenderer;

    private ComponentSystemManager componentSystemManager;
    private LocalPlayerSystem localPlayerSys;
    private CameraTargetSystem cameraTargetSystem;
    private InputSystem inputSystem;

    /* GAME LOOP */
    private boolean pauseGame = false;

    public StateSinglePlayer(String worldName) {
        this(worldName, null);
    }

    public StateSinglePlayer(String worldName, String seed) {
        this.currentWorldName = worldName;
        this.currentWorldSeed = seed;
    }

    public void init(GameEngine engine) {
        // TODO: Change to better mod support, should be enabled via config
        ModManager modManager = new ModManager();
        for (Mod mod : modManager.getMods()) {
             mod.setEnabled(true);
        }
        modManager.saveModSelectionToConfig();
        cacheTextures();

        entityManager = new EntitySystemBuilder().build();

        componentSystemManager = new ComponentSystemManager();
        CoreRegistry.put(ComponentSystemManager.class, componentSystemManager);
        localPlayerSys = new LocalPlayerSystem();
        componentSystemManager.register(localPlayerSys, "engine:LocalPlayerSystem");
        cameraTargetSystem = new CameraTargetSystem();
        CoreRegistry.put(CameraTargetSystem.class, cameraTargetSystem);
        componentSystemManager.register(cameraTargetSystem, "engine:CameraTargetSystem");
        inputSystem = new InputSystem();
        CoreRegistry.put(InputSystem.class, inputSystem);
        componentSystemManager.register(inputSystem, "engine:InputSystem");

        componentSystemManager.loadEngineSystems();
        componentSystemManager.loadSystems("miniions", "org.terasology.mods.miniions");

        CoreRegistry.put(WorldPersister.class, new WorldPersister(entityManager));

        // TODO: Use reflection pending mod support
        EventSystem eventSystem = entityManager.getEventSystem();
        eventSystem.registerEvent("engine:inputEvent", InputEvent.class);
        eventSystem.registerEvent("engine:keyDownEvent", KeyDownEvent.class);
        eventSystem.registerEvent("engine:keyEvent", KeyEvent.class);
        eventSystem.registerEvent("engine:keyUpEvent", KeyUpEvent.class);
        eventSystem.registerEvent("engine:keyRepeatEvent", KeyRepeatEvent.class);
        eventSystem.registerEvent("engine:leftMouseDownButtonEvent", LeftMouseDownButtonEvent.class);
        eventSystem.registerEvent("engine:leftMouseUpButtonEvent", LeftMouseUpButtonEvent.class);
        eventSystem.registerEvent("engine:mouseDownButtonEvent", MouseDownButtonEvent.class);
        eventSystem.registerEvent("engine:mouseUpButtonEvent", MouseUpButtonEvent.class);
        eventSystem.registerEvent("engine:mouseButtonEvent", MouseButtonEvent.class);
        eventSystem.registerEvent("engine:mouseWheelEvent", MouseWheelEvent.class);
        eventSystem.registerEvent("engine:rightMouseDownButtonEvent", RightMouseDownButtonEvent.class);
        eventSystem.registerEvent("engine:rightMouseUpButtonEvent", RightMouseUpButtonEvent.class);
        eventSystem.registerEvent("engine:bindButtonEvent", BindButtonEvent.class);
        eventSystem.registerEvent("engine:inventoryButtonEvent", InventoryButton.class);

        loadPrefabs();
    }

    private void loadPrefabs() {
        EntityPersisterHelper persisterHelper = new EntityPersisterHelperImpl(entityManager);
        for (AssetUri prefabURI : AssetManager.list(AssetType.PREFAB)) {
            logger.info("Loading prefab " + prefabURI);
            try {
                InputStream stream = AssetManager.assetStream(prefabURI);
                if (stream != null) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(stream));
                    EntityData.Prefab prefabData = EntityDataJSONFormat.readPrefab(reader);
                    stream.close();
                    if (prefabData != null) {
                        persisterHelper.deserializePrefab(prefabData, prefabURI.getPackage());
                    }
                } else {
                    logger.severe("Failed to load prefab '" + prefabURI + "'");
                }
            } catch (IOException e) {
                logger.log(Level.WARNING, "Failed to load prefab '" + prefabURI + "'", e);
            }
        }
    }

    private void cacheTextures() {
        for (AssetUri textureURI : AssetManager.list(AssetType.TEXTURE)) {
            AssetManager.load(textureURI);
        }
    }

    @Override
    public void activate() {
        initWorld(currentWorldName, currentWorldSeed);
    }

    @Override
    public void deactivate() {
        for (ComponentSystem system : componentSystemManager.iterateAll()) {
            system.shutdown();
        }
        GUIManager.getInstance().closeWindows();
        try {
            CoreRegistry.get(WorldPersister.class).save(new File(PathManager.getInstance().getWorldSavePath(CoreRegistry.get(WorldProvider.class).getTitle()), ENTITY_DATA_FILE), WorldPersister.SaveFormat.Binary);
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Failed to save entities", e);
        }
        dispose();
        entityManager.clear();
    }

    @Override
    public void dispose() {
        if (worldRenderer != null) {
            worldRenderer.dispose();
            worldRenderer = null;
        }
    }

    @Override
    public void update(float delta) {
        /* GUI */
        updateUserInterface();
        
        for (UpdateSubscriberSystem updater : componentSystemManager.iterateUpdateSubscribers()) {
            PerformanceMonitor.startActivity(updater.getClass().getSimpleName());
            updater.update(delta);
        }

        if (worldRenderer != null && shouldUpdateWorld()) {
            worldRenderer.update(delta);
        }

        /* TODO: This seems a little off - plus is more of a UI than single player game state concern. Move somewhere
           more appropriate? Possibly HUD? */
        boolean dead = true;
        for (EntityRef entity : entityManager.iteratorEntities(LocalPlayerComponent.class))
        {
            dead = entity.getComponent(LocalPlayerComponent.class).isDead;
        }
        if (dead) {
            if (GUIManager.getInstance().getWindowById("engine:statusScreen") == null) {
                UIStatusScreen statusScreen = GUIManager.getInstance().addWindow(new UIStatusScreen(), "engine:statusScreen");
                statusScreen.updateStatus("Sorry! Seems like you have died :-(");
                statusScreen.setVisible(true);
            }
        } else {
            GUIManager.getInstance().removeWindow("engine:statusScreen");
        }
    }

    @Override
    public void handleInput(float delta) {
        cameraTargetSystem.update();
        inputSystem.update(delta);

        // TODO: This should be handled outside of the state, need to fix the screens handling
            if (screenHasFocus() || !shouldUpdateWorld()) {
                if (Mouse.isGrabbed()) {
                    Mouse.setGrabbed(false);
                    Mouse.setCursorPosition(Display.getWidth() / 2, Display.getHeight() / 2);
                }
            } else {
            if (!Mouse.isGrabbed()) {
                    Mouse.setGrabbed(true);
            }
        }
        }

    public void initWorld(String title) {
        initWorld(title, null);
    }

    /**
     * Init. a new random world.
     */
    public void initWorld(String title, String seed) {
        final FastRandom random = new FastRandom();

        // Get rid of the old world
        if (worldRenderer != null) {
            worldRenderer.dispose();
            worldRenderer = null;
        }

        if (seed == null) {
            seed = random.randomCharacterString(16);
        } else if (seed.isEmpty()) {
            seed = random.randomCharacterString(16);
        }

        logger.log(Level.INFO, "Creating new World with seed \"{0}\"", seed);

        // Init. a new world
        worldRenderer = new WorldRenderer(title, seed, entityManager, localPlayerSys);
        CoreRegistry.put(WorldRenderer.class, worldRenderer);

        File entityDataFile = new File(PathManager.getInstance().getWorldSavePath(title), ENTITY_DATA_FILE);
        entityManager.clear();
        if (entityDataFile.exists()) {
            try {
                CoreRegistry.get(WorldPersister.class).load(entityDataFile, WorldPersister.SaveFormat.Binary);
            } catch (IOException e) {
                logger.log(Level.SEVERE, "Failed to load entity data", e);
            }
        }

        CoreRegistry.put(WorldRenderer.class, worldRenderer);
        CoreRegistry.put(WorldProvider.class, worldRenderer.getWorldProvider());
        CoreRegistry.put(LocalPlayer.class, new LocalPlayer(EntityRef.NULL));
        CoreRegistry.put(Camera.class, worldRenderer.getActiveCamera());
        CoreRegistry.put(BulletPhysicsRenderer.class, worldRenderer.getBulletRenderer());

        for (ComponentSystem system : componentSystemManager.iterateAll()) {
            system.initialise();
        }

        prepareWorld();
    }

    private Vector3f nextSpawningPoint() {
        return new Vector3f(0,5,0);
        // TODO: Need to generate an X/Z coord, force a chunk relevent and calculate Y
        /*
        ChunkGeneratorTerrain tGen = ((ChunkGeneratorTerrain) getGeneratorManager().getChunkGenerators().get(0));

        FastRandom nRandom = new FastRandom(CoreRegistry.get(Timer.class).getTimeInMs());

        for (; ; ) {
            int randX = (int) (nRandom.randomDouble() * 128f);
            int randZ = (int) (nRandom.randomDouble() * 128f);

            for (int y = Chunk.SIZE_Y - 1; y >= 32; y--) {

                double dens = tGen.calcDensity(randX + (int) SPAWN_ORIGIN.x, y, randZ + (int) SPAWN_ORIGIN.y);

                if (dens >= 0 && y < 64)
                    return new Vector3d(randX + SPAWN_ORIGIN.x, y, randZ + SPAWN_ORIGIN.y);
                else if (dens >= 0 && y >= 64)
                    break;
            }
        } */
    }


    private boolean screenHasFocus() {
        return GUIManager.getInstance().getFocusedWindow() != null && GUIManager.getInstance().getFocusedWindow().isModal() && GUIManager.getInstance().getFocusedWindow().isVisible();
            }

    private boolean shouldUpdateWorld() {
        return !pauseGame;
    }

    // TODO: Maybe should have its own state?
    private void prepareWorld() {
        UILoadingScreen loadingScreen = GUIManager.getInstance().addWindow(new UILoadingScreen(), "engine:loadingScreen");
        Display.update();

        int chunksGenerated = 0;

        Timer timer = CoreRegistry.get(Timer.class);
        long startTime = timer.getTimeInMs();

        Iterator<EntityRef> iterator = entityManager.iteratorEntities(LocalPlayerComponent.class).iterator();
        if (iterator.hasNext()) {
            CoreRegistry.get(LocalPlayer.class).setEntity(iterator.next());
            worldRenderer.setPlayer(CoreRegistry.get(LocalPlayer.class));
        } else {
            // Load spawn zone so player spawn location can be determined
            EntityRef spawnZoneEntity = entityManager.create();
            spawnZoneEntity.addComponent(new LocationComponent(new Vector3f(Chunk.SIZE_X / 2, Chunk.SIZE_Y / 2, Chunk.SIZE_Z / 2)));
            worldRenderer.getChunkProvider().addRegionEntity(spawnZoneEntity, 1);

            while (!worldRenderer.getWorldProvider().isBlockActive(new Vector3i(Chunk.SIZE_X / 2, Chunk.SIZE_Y / 2, Chunk.SIZE_Z / 2))) {
                loadingScreen.updateStatus(String.format("Loading spawn area... %.2f%%! :-)", (timer.getTimeInMs() - startTime) / 50.0f));

                renderUserInterface();
                updateUserInterface();
                Display.update();
            }

            Vector3i spawnPoint = new Vector3i(Chunk.SIZE_X / 2, Chunk.SIZE_Y, Chunk.SIZE_Z / 2);
            while (worldRenderer.getWorldProvider().getBlock(spawnPoint) == BlockManager.getInstance().getAir() && spawnPoint.y > 0) {
                spawnPoint.y--;
            }

            PlayerFactory playerFactory = new PlayerFactory(entityManager);
            CoreRegistry.get(LocalPlayer.class).setEntity(playerFactory.newInstance(new Vector3f(spawnPoint.x + 0.5f, spawnPoint.y + 2.0f, spawnPoint.z + 0.5f)));
            worldRenderer.setPlayer(CoreRegistry.get(LocalPlayer.class));
            worldRenderer.getChunkProvider().removeRegionEntity(spawnZoneEntity);
            spawnZoneEntity.destroy();
        }

        while (!getWorldRenderer().pregenerateChunks() && timer.getTimeInMs() - startTime < 5000) {
            chunksGenerated++;

            loadingScreen.updateStatus(String.format("Fast forwarding world... %.2f%%! :-)", (timer.getTimeInMs() - startTime) / 50.0f));

            renderUserInterface();
            updateUserInterface();
            Display.update();
        }

        GUIManager.getInstance().removeWindow(loadingScreen);

        // Create the first Portal if it doesn't exist yet
        worldRenderer.initPortal();
    }

    public void render() {
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        glLoadIdentity();

        if (worldRenderer != null) {
            worldRenderer.render();
        }

        /* UI */
        PerformanceMonitor.startActivity("Render and Update UI");
        renderUserInterface();
        PerformanceMonitor.endActivity();
    }

    public void renderUserInterface() {
        GUIManager.getInstance().render();
    }

    private void updateUserInterface() {
        GUIManager.getInstance().update();
    }

    public WorldRenderer getWorldRenderer() {
        return worldRenderer;
    }

    public void pause() {
        pauseGame = true;
    }

    public void unpause() {
        pauseGame = false;
    }

    public void togglePauseGame() {
        if (pauseGame) {
            unpause();
        } else {
            pause();
        }
    }

    public boolean isGamePaused() {
        return pauseGame;
    }

}
