/**
 * Copyright (c) 2010-2020 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.miio.internal.handler;

import static org.openhab.binding.miio.internal.MiIoBindingConstants.CHANNEL_COMMAND;

import java.awt.Color;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.smarthome.core.cache.ExpiringCache;
import org.eclipse.smarthome.core.library.types.DecimalType;
import org.eclipse.smarthome.core.library.types.HSBType;
import org.eclipse.smarthome.core.library.types.OnOffType;
import org.eclipse.smarthome.core.library.types.StringType;
import org.eclipse.smarthome.core.thing.Channel;
import org.eclipse.smarthome.core.thing.ChannelUID;
import org.eclipse.smarthome.core.thing.Thing;
import org.eclipse.smarthome.core.thing.binding.builder.ChannelBuilder;
import org.eclipse.smarthome.core.thing.binding.builder.ThingBuilder;
import org.eclipse.smarthome.core.thing.type.ChannelTypeUID;
import org.eclipse.smarthome.core.types.Command;
import org.eclipse.smarthome.core.types.RefreshType;
import org.openhab.binding.miio.internal.MiIoBindingConfiguration;
import org.openhab.binding.miio.internal.MiIoCommand;
import org.openhab.binding.miio.internal.MiIoCryptoException;
import org.openhab.binding.miio.internal.MiIoSendCommand;
import org.openhab.binding.miio.internal.Utils;
import org.openhab.binding.miio.internal.basic.CommandParameterType;
import org.openhab.binding.miio.internal.basic.Conversions;
import org.openhab.binding.miio.internal.basic.MiIoBasicChannel;
import org.openhab.binding.miio.internal.basic.MiIoBasicDevice;
import org.openhab.binding.miio.internal.basic.MiIoDatabaseWatchService;
import org.openhab.binding.miio.internal.basic.MiIoDeviceAction;
import org.openhab.binding.miio.internal.transport.MiIoAsyncCommunication;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonIOException;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;

/**
 * The {@link MiIoBasicHandler} is responsible for handling commands, which are
 * sent to one of the channels.
 *
 * @author Marcel Verpaalen - Initial contribution
 */
@NonNullByDefault
public class MiIoBasicHandler extends MiIoAbstractHandler {

    private final Logger logger = LoggerFactory.getLogger(MiIoBasicHandler.class);
    private boolean hasChannelStructure;

    private final ExpiringCache<Boolean> updateDataCache = new ExpiringCache<>(CACHE_EXPIRY, () -> {
        scheduler.schedule(this::updateData, 0, TimeUnit.SECONDS);
        return true;
    });

    List<MiIoBasicChannel> refreshList = new ArrayList<>();

    private @Nullable MiIoBasicDevice miioDevice;
    private Map<String, MiIoDeviceAction> actions = new HashMap<>();

    public MiIoBasicHandler(Thing thing, MiIoDatabaseWatchService miIoDatabaseWatchService) {
        super(thing, miIoDatabaseWatchService);
    }

    @Override
    public void initialize() {
        super.initialize();
        hasChannelStructure = false;
        isIdentified = false;
        refreshList = new ArrayList<>();
    }

    @Override
    public void dispose() {
        logger.debug("Disposing Xiaomi Mi IO Basic handler '{}'", getThing().getUID());
        final @Nullable ScheduledFuture<?> pollingJob = this.pollingJob;
        if (pollingJob != null) {
            pollingJob.cancel(true);
        }
        this.pollingJob = null;
        final @Nullable MiIoAsyncCommunication miioCom = this.miioCom;
        if (miioCom != null) {
            lastId = miioCom.getId();
            miioCom.close();
            this.miioCom = null;
        }
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        if (command == RefreshType.REFRESH) {
            if (updateDataCache.isExpired()) {
                logger.debug("Refreshing {}", channelUID);
                updateDataCache.getValue();
            } else {
                logger.debug("Refresh {} skipped. Already refreshing", channelUID);
            }
            return;
        }
        if (channelUID.getId().equals(CHANNEL_COMMAND)) {
            cmds.put(sendCommand(command.toString()), command.toString());
        }
        logger.debug("Locating action for channel {}: {}", channelUID.getId(), command);
        if (!actions.isEmpty()) {
            if (actions.containsKey(channelUID.getId())) {
                String preCommandPara1 = actions.get(channelUID.getId()).getPreCommandParameter1();
                preCommandPara1 = ((preCommandPara1 != null && !preCommandPara1.isEmpty()) ? preCommandPara1 + ","
                        : "");
                String para1 = actions.get(channelUID.getId()).getParameter1();
                String para2 = actions.get(channelUID.getId()).getParameter2();
                String para3 = actions.get(channelUID.getId()).getParameter3();
                String para = "" + (para1 != null ? "," + para1 : "") + (para2 != null ? "," + para2 : "")
                        + (para3 != null ? "," + para3 : "");
                String cmd = actions.get(channelUID.getId()).getCommand();
                CommandParameterType paramType = actions.get(channelUID.getId()).getparameterType();
                if (paramType == CommandParameterType.EMPTY) {
                    cmd = cmd + "[]";
                } else if (paramType == CommandParameterType.NONE) {
                    logger.trace("NONE command type");
                } else if (paramType == CommandParameterType.COLOR) {
                    if (command instanceof HSBType) {
                        HSBType hsb = (HSBType) command;
                        Color color = Color.getHSBColor(hsb.getHue().floatValue() / 360,
                                hsb.getSaturation().floatValue() / 100, hsb.getBrightness().floatValue() / 100);
                        cmd = cmd + "[" + preCommandPara1
                                + ((color.getRed() * 65536) + (color.getGreen() * 256) + color.getBlue()) + para + "]";
                    } else if (command instanceof DecimalType) {
                        // actually brightness is being set instead of a color
                        cmd = "set_bright" + "[" + command.toString().toLowerCase() + "]";
                    } else {
                        logger.debug("Unsupported command for COLOR: {}", command);
                    }

                } else if (command instanceof OnOffType) {
                    if (paramType == CommandParameterType.ONOFF) {
                        cmd = cmd + "[" + preCommandPara1 + "\"" + command.toString().toLowerCase() + "\"" + para + "]";
                    } else if (paramType == CommandParameterType.ONOFFPARA) {
                        cmd = cmd.replace("*", command.toString().toLowerCase()) + "[]";
                    } else if (paramType == CommandParameterType.ONOFFBOOL) {
                        boolean boolCommand = command == OnOffType.ON;
                        cmd = cmd + "[" + preCommandPara1 + "\"" + boolCommand + "\"" + para + "]";
                    } else {
                        cmd = cmd + "[]";
                    }
                } else if (command instanceof StringType) {
                    if (paramType == CommandParameterType.STRING) {
                        cmd = cmd + "[" + preCommandPara1 + "\"" + command.toString() + "\"" + para + "]";
                    } else if (paramType == CommandParameterType.CUSTOMSTRING) {
                        cmd = cmd + "[" + preCommandPara1 + "\"" + command.toString() + para + "]";
                    }
                } else if (command instanceof DecimalType) {
                    cmd = cmd + "[" + preCommandPara1 + command.toString().toLowerCase() + para + "]";
                }
                logger.debug("Sending command {}", cmd);
                sendCommand(cmd);
            } else {
                logger.debug("Channel Id {} not in mapping.", channelUID.getId());
                for (String a : actions.keySet()) {
                    logger.trace("Available entries: {} : {}", a, actions.get(a).getCommand());
                }
            }
            updateDataCache.invalidateValue();
            updateData();
        } else {
            logger.debug("Actions not loaded yet");
        }
    }

    @Override
    protected synchronized void updateData() {
        final MiIoBindingConfiguration configuration = getConfigAs(MiIoBindingConfiguration.class);
        logger.debug("Periodic update for '{}' ({})", getThing().getUID().toString(), getThing().getThingTypeUID());
        final MiIoAsyncCommunication miioCom = this.miioCom;
        try {
            if (!hasConnection() || skipUpdate()) {
                return;
            }
            if (miioCom == null || !initializeData()) {
                return;
            }
            try {
                miioCom.startReceiver();
                miioCom.sendPing(configuration.host);
            } catch (Exception e) {
                // ignore
            }
            checkChannelStructure();
            if (!isIdentified) {
                miioCom.queueCommand(MiIoCommand.MIIO_INFO);
            }
            final MiIoBasicDevice midevice = miioDevice;
            if (midevice != null) {
                refreshProperties(midevice);
                refreshNetwork();
            }
        } catch (Exception e) {
            logger.debug("Error while updating '{}': ", getThing().getUID().toString(), e);
        }
    }

    private boolean refreshProperties(MiIoBasicDevice device) {
        MiIoCommand command = MiIoCommand.getCommand(device.getDevice().getPropertyMethod());
        int maxProperties = device.getDevice().getMaxProperties();
        JsonArray getPropString = new JsonArray();
        for (MiIoBasicChannel miChannel : refreshList) {
            getPropString.add(miChannel.getProperty());
            if (getPropString.size() >= maxProperties) {
                sendRefreshProperties(command, getPropString);
                getPropString = new JsonArray();
            }
        }
        if (getPropString.size() > 0) {
            sendRefreshProperties(command, getPropString);
        }
        return true;
    }

    private void sendRefreshProperties(MiIoCommand command, JsonArray getPropString) {
        try {
            final MiIoAsyncCommunication miioCom = this.miioCom;
            if (miioCom != null) {
                miioCom.queueCommand(command, getPropString.toString());
            }
        } catch (MiIoCryptoException | IOException e) {
            logger.debug("Send refresh failed {}", e.getMessage(), e);
        }
    }

    @Override
    protected boolean initializeData() {
        final MiIoBindingConfiguration configuration = getConfigAs(MiIoBindingConfiguration.class);
        final MiIoAsyncCommunication miioCom = new MiIoAsyncCommunication(configuration.host, token,
                Utils.hexStringToByteArray(configuration.deviceId), lastId, configuration.timeout);
        miioCom.registerListener(this);
        try {
            miioCom.sendPing(configuration.host);
        } catch (IOException e) {
            logger.debug("ping {} failed", configuration.host);
        }
        this.miioCom = miioCom;
        return true;
    }

    /**
     * Checks if the channel structure has been build already based on the model data. If not build it.
     */
    private void checkChannelStructure() {
        final MiIoBindingConfiguration configuration = getConfigAs(MiIoBindingConfiguration.class);
        if (!hasChannelStructure) {
            if (configuration.model == null || configuration.model.isEmpty()) {
                logger.debug("Model needs to be determined");
            } else {
                hasChannelStructure = buildChannelStructure(configuration.model);
            }
        }
        if (hasChannelStructure) {
            refreshList = new ArrayList<>();
            final MiIoBasicDevice miioDevice = this.miioDevice;
            if (miioDevice != null) {
                for (MiIoBasicChannel miChannel : miioDevice.getDevice().getChannels()) {
                    if (miChannel.getRefresh()) {
                        refreshList.add(miChannel);
                    }
                }
            }

        }
    }

    private boolean buildChannelStructure(String deviceName) {
        logger.debug("Building Channel Structure for {} - Model: {}", getThing().getUID().toString(), deviceName);
        URL fn = miIoDatabaseWatchService.getDatabaseUrl(deviceName);
        if (fn == null) {
            logger.warn("Database entry for model '{}' cannot be found.", deviceName);
            return false;
        }
        try {
            JsonObject deviceMapping = Utils.convertFileToJSON(fn);
            logger.debug("Using device database: {} for device {}", fn.getFile(), deviceName);
            Gson gson = new GsonBuilder().serializeNulls().create();
            miioDevice = gson.fromJson(deviceMapping, MiIoBasicDevice.class);
            for (Channel ch : getThing().getChannels()) {
                logger.debug("Current thing channels {}, type: {}", ch.getUID(), ch.getChannelTypeUID());
            }
            ThingBuilder thingBuilder = editThing();
            int channelsAdded = 0;

            // make a map of the actions
            actions = new HashMap<>();
            final MiIoBasicDevice device = this.miioDevice;
            if (device != null) {
                for (MiIoBasicChannel miChannel : device.getDevice().getChannels()) {
                    logger.debug("properties {}", miChannel);
                    for (MiIoDeviceAction action : miChannel.getActions()) {
                        actions.put(miChannel.getChannel(), action);
                    }
                    if (!miChannel.getType().isEmpty()) {
                        channelsAdded += addChannel(thingBuilder, miChannel.getChannel(), miChannel.getChannelType(),
                                miChannel.getType(), miChannel.getFriendlyName()) ? 1 : 0;
                    }
                }
            }
            // only update if channels were added/removed
            if (channelsAdded > 0) {
                logger.debug("Current thing channels added: {}", channelsAdded);
                updateThing(thingBuilder.build());
            }
            return true;
        } catch (JsonIOException e) {
            logger.warn("Error reading database Json", e);
        } catch (JsonSyntaxException e) {
            logger.warn("Error reading database Json", e);
        } catch (IOException e) {
            logger.warn("Error reading database file", e);
        } catch (Exception e) {
            logger.warn("Error creating channel structure", e);
        }
        return false;
    }

    private boolean addChannel(ThingBuilder thingBuilder, @Nullable String channel, String channelType,
            @Nullable String datatype, String friendlyName) {
        if (channel == null || channel.isEmpty() || datatype == null || datatype.isEmpty()) {
            logger.info("Channel '{}', UID '{}' cannot be added incorrectly configured database. ", channel,
                    getThing().getUID());
            return false;
        }
        ChannelUID channelUID = new ChannelUID(getThing().getUID(), channel);
        ChannelTypeUID channelTypeUID = new ChannelTypeUID(channelType);

        // TODO: Need to understand if this harms anything. If yes, channel only to be added when not there already.
        // current way allows to have no issues when channels are changing.
        if (getThing().getChannel(channel) != null) {
            logger.info("Channel '{}' for thing {} already exist... removing", channel, getThing().getUID());
            thingBuilder.withoutChannel(new ChannelUID(getThing().getUID(), channel));
        }

        Channel newChannel = ChannelBuilder.create(channelUID, datatype).withType(channelTypeUID)
                .withLabel(friendlyName).build();
        thingBuilder.withChannel(newChannel);
        return true;
    }

    private @Nullable MiIoBasicChannel getChannel(String parameter) {
        for (MiIoBasicChannel refreshEntry : refreshList) {
            if (refreshEntry.getProperty().equals(parameter)) {
                return refreshEntry;
            }
        }
        logger.trace("Did not find channel for {} in {}", parameter, refreshList);
        return null;
    }

    private void updatePropsFromJsonArray(MiIoSendCommand response) {
        JsonArray res = response.getResult().getAsJsonArray();
        JsonArray para = parser.parse(response.getCommandString()).getAsJsonObject().get("params").getAsJsonArray();
        if (res.size() != para.size()) {
            logger.debug("Unexpected size different. Request size {},  response size {}. (Req: {}, Resp:{})",
                    para.size(), res.size(), para, res);
        }
        for (int i = 0; i < para.size(); i++) {
            String param = para.get(i).getAsString();
            JsonElement val = res.get(i);
            if (val.isJsonNull()) {
                logger.debug("Property '{}' returned null (is it supported?).", param);
                continue;
            }
            MiIoBasicChannel basicChannel = getChannel(param);
            updateChannel(basicChannel, param, val);
        }
    }

    private void updatePropsFromJsonObject(MiIoSendCommand response) {
        JsonObject res = response.getResult().getAsJsonObject();
        for (Object k : res.keySet()) {
            String param = (String) k;
            JsonElement val = res.get(param);
            if (val.isJsonNull()) {
                logger.debug("Property '{}' returned null (is it supported?).", param);
                continue;
            }
            MiIoBasicChannel basicChannel = getChannel(param);
            updateChannel(basicChannel, param, val);
        }
    }

    private void updateChannel(@Nullable MiIoBasicChannel basicChannel, String param, JsonElement value) {
        JsonElement val = value;
        if (basicChannel == null) {
            logger.debug("Channel not found for {}", param);
            return;
        }
        final String transformation = basicChannel.getTransfortmation();
        if (transformation != null) {
            JsonElement transformed = Conversions.execute(transformation, val);
            logger.debug("Transformed with '{}': {} {} -> {} ", transformation, basicChannel.getFriendlyName(), val,
                    transformed);
            val = transformed;
        }
        try {
            switch (basicChannel.getType().toLowerCase()) {
                case "number":
                    updateState(basicChannel.getChannel(), new DecimalType(val.getAsBigDecimal()));
                    break;
                case "string":
                    updateState(basicChannel.getChannel(), new StringType(val.getAsString()));
                    break;
                case "switch":
                    updateState(basicChannel.getChannel(), val.getAsString().toLowerCase().equals("on")
                            || val.getAsString().toLowerCase().equals("true") ? OnOffType.ON : OnOffType.OFF);
                    break;
                case "color":
                    Color rgb = new Color(val.getAsInt());
                    HSBType hsb = HSBType.fromRGB(rgb.getRed(), rgb.getGreen(), rgb.getBlue());
                    updateState(basicChannel.getChannel(), hsb);
                    break;
                default:
                    logger.debug("No update logic for channeltype '{}' ", basicChannel.getType());
            }
        } catch (Exception e) {
            logger.debug("Error updating {} property {} with '{}' : {}: {}", getThing().getUID(),
                    basicChannel.getChannel(), val, e.getClass().getCanonicalName(), e.getMessage());
            logger.trace("Property update error detail:", e);
        }
    }

    @Override
    public void onMessageReceived(MiIoSendCommand response) {
        super.onMessageReceived(response);
        if (response.isError()) {
            return;
        }
        try {
            switch (response.getCommand()) {
                case MIIO_INFO:
                    break;
                case GET_VALUE:
                case GET_PROPERTY:
                    if (response.getResult().isJsonArray()) {
                        updatePropsFromJsonArray(response);
                    } else if (response.getResult().isJsonObject()) {
                        updatePropsFromJsonObject(response);
                    }
                    break;
                default:
                    break;
            }
        } catch (Exception e) {
            logger.debug("Error while handing message {}", response.getResponse(), e);
        }
    }
}
