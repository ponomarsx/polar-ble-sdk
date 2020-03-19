// Copyright © 2019 Polar Electro Oy. All rights reserved.
package polar.com.sdk.impl;

import android.annotation.SuppressLint;
import android.bluetooth.le.ScanFilter;
import android.content.Context;
import android.os.Build;
import android.os.ParcelUuid;
import android.support.annotation.Nullable;
import android.util.Log;
import android.util.Pair;

import com.androidcommunications.polar.api.ble.BleDeviceListener;
import com.androidcommunications.polar.api.ble.BleLogger;
import com.androidcommunications.polar.api.ble.exceptions.BleDisconnected;
import com.androidcommunications.polar.api.ble.model.BleDeviceSession;
import com.androidcommunications.polar.api.ble.model.advertisement.BleAdvertisementContent;
import com.androidcommunications.polar.api.ble.model.advertisement.BlePolarHrAdvertisement;
import com.androidcommunications.polar.api.ble.model.gatt.BleGattBase;
import com.androidcommunications.polar.api.ble.model.gatt.client.BleBattClient;
import com.androidcommunications.polar.api.ble.model.gatt.client.BleDisClient;
import com.androidcommunications.polar.api.ble.model.gatt.client.BleHrClient;
import com.androidcommunications.polar.api.ble.model.gatt.client.BlePMDClient;
import com.androidcommunications.polar.api.ble.model.gatt.client.psftp.BlePsFtpClient;
import com.androidcommunications.polar.api.ble.model.gatt.client.psftp.BlePsFtpUtils;
import com.androidcommunications.polar.common.ble.BleUtils;
import com.androidcommunications.polar.enpoints.ble.bluedroid.host.BDDeviceListenerImpl;

import org.reactivestreams.Publisher;

import java.io.ByteArrayOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import fi.polar.remote.representation.protobuf.ExerciseSamples;
import fi.polar.remote.representation.protobuf.Types;
import io.reactivex.Completable;
import io.reactivex.CompletableEmitter;
import io.reactivex.CompletableOnSubscribe;
import io.reactivex.CompletableSource;
import io.reactivex.Flowable;
import io.reactivex.Scheduler;
import io.reactivex.Single;
import io.reactivex.SingleSource;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.Disposable;
import io.reactivex.functions.Action;
import io.reactivex.functions.BiFunction;
import io.reactivex.functions.Consumer;
import io.reactivex.functions.Function;
import io.reactivex.functions.Predicate;
import io.reactivex.schedulers.Timed;
import polar.com.sdk.api.PolarBleApi;
import polar.com.sdk.api.PolarBleApiCallback;
import polar.com.sdk.api.PolarBleApiCallbackProvider;
import polar.com.sdk.api.errors.PolarDeviceDisconnected;
import polar.com.sdk.api.errors.PolarDeviceNotFound;
import polar.com.sdk.api.errors.PolarInvalidArgument;
import polar.com.sdk.api.errors.PolarNotificationNotEnabled;
import polar.com.sdk.api.errors.PolarOperationNotSupported;
import polar.com.sdk.api.errors.PolarServiceNotAvailable;
import polar.com.sdk.api.model.PolarAccelerometerData;
import polar.com.sdk.api.model.PolarBiozData;
import polar.com.sdk.api.model.PolarDeviceInfo;
import polar.com.sdk.api.model.PolarEcgData;
import polar.com.sdk.api.model.PolarExerciseData;
import polar.com.sdk.api.model.PolarExerciseEntry;
import polar.com.sdk.api.model.PolarHrBroadcastData;
import polar.com.sdk.api.model.PolarHrData;
import polar.com.sdk.api.model.PolarOhrPPGData;
import polar.com.sdk.api.model.PolarOhrPPIData;
import polar.com.sdk.api.model.PolarSensorSetting;
import protocol.PftpNotification;
import protocol.PftpRequest;
import protocol.PftpResponse;

/**
 *The default implementation of the Polar API
 */
public class BDBleApiImpl extends PolarBleApi {
    protected final static String TAG = BDBleApiImpl.class.getSimpleName();
    protected BleDeviceListener listener;
    protected Map<String,Disposable> connectSubscriptions = new HashMap<>();
    protected Scheduler scheduler;
    protected PolarBleApiCallbackProvider callback;
    protected PolarBleApiLogger logger;
    protected static final int ANDROID_VERSION_O = 26;
    BleDeviceListener.BleSearchPreFilter filter = new BleDeviceListener.BleSearchPreFilter() {
        @Override
        public boolean process(BleAdvertisementContent content) {
            return content.getPolarDeviceId().length() != 0 && !content.getPolarDeviceType().equals("mobile");
        }
    };

    @SuppressLint({"NewApi", "CheckResult"})
    public BDBleApiImpl(final Context context, int features) {
        super(features);
        Set<Class<? extends BleGattBase>> clients = new HashSet<>();
        if((this.features & PolarBleApi.FEATURE_HR)!=0){
            clients.add(BleHrClient.class);
        }
        if((this.features & PolarBleApi.FEATURE_DEVICE_INFO)!=0){
            clients.add(BleDisClient.class);
        }
        if((this.features & PolarBleApi.FEATURE_BATTERY_INFO)!=0){
            clients.add(BleBattClient.class);
        }
        if((this.features & PolarBleApi.FEATURE_POLAR_SENSOR_STREAMING)!=0){
            clients.add(BlePMDClient.class);
        }
        if((this.features & PolarBleApi.FEATURE_POLAR_FILE_TRANSFER)!=0){
            clients.add(BlePsFtpClient.class);
        }
        listener = new BDDeviceListenerImpl(context, clients);
        listener.setScanPreFilter(filter);
        scheduler = AndroidSchedulers.from(context.getMainLooper());
        listener.monitorDeviceSessionState(null).observeOn(scheduler).subscribe(
                new Consumer<Pair<BleDeviceSession, BleDeviceSession.DeviceSessionState>>() {
                    @Override
                    public void accept(Pair<BleDeviceSession, BleDeviceSession.DeviceSessionState> pair) throws Exception {
                        PolarDeviceInfo info = new PolarDeviceInfo(
                                pair.first.getPolarDeviceId().length() != 0 ?
                                        pair.first.getPolarDeviceId() : pair.first.getAddress(),
                                pair.first.getAddress(),
                                pair.first.getRssi(),pair.first.getName(),true);
                        switch (pair.second){
                            case SESSION_OPEN:
                                if(callback!=null){
                                    callback.deviceConnected(info);
                                }
                                setupDevice(pair.first);
                                break;
                            case SESSION_CLOSED:
                                if( callback != null ) {
                                    if (pair.first.getPreviousState() == BleDeviceSession.DeviceSessionState.SESSION_OPEN ||
                                            pair.first.getPreviousState() == BleDeviceSession.DeviceSessionState.SESSION_CLOSING){
                                        callback.deviceDisconnected(info);
                                    }
                                }
                                break;
                            case SESSION_OPENING:
                                if(callback != null){
                                    callback.deviceConnecting(info);
                                }
                                break;
                        }
                    }
                },
                new Consumer<Throwable>() {
                    @Override
                    public void accept(Throwable throwable) throws Exception {
                        logError(throwable.getMessage());
                    }
                },
                new Action() {
                    @Override
                    public void run() throws Exception {

                    }
                }
        );
        listener.monitorBleState().observeOn(scheduler).subscribe(
                new Consumer<Boolean>() {
                    @Override
                    public void accept(Boolean aBoolean) throws Exception {
                        if(callback != null){
                            callback.blePowerStateChanged(aBoolean);
                        }
                    }
                },
                new Consumer<Throwable>() {
                    @Override
                    public void accept(Throwable throwable) throws Exception {
                        logError(throwable.getMessage());
                    }
                },
                new Action() {
                    @Override
                    public void run() throws Exception {

                    }
                }
        );
        BleLogger.setLoggerInterface(new BleLogger.BleLoggerInterface() {
            @Override
            public void d(String tag, String msg) {
                log(tag+"/"+msg);
            }

            @Override
            public void e(String tag, String msg) {
                logError(tag+"/"+msg);
            }

            @Override
            public void w(String tag, String msg) {
            }

            @Override
            public void i(String tag, String msg) {
            }
        });
    }

    @SuppressLint("NewApi")
    protected void enableAndroidScanFilter() {
        if (Build.VERSION.SDK_INT >= ANDROID_VERSION_O) {
            List<ScanFilter> filter = new ArrayList<>();
            filter.add(new ScanFilter.Builder().setServiceUuid(
                    ParcelUuid.fromString(BleHrClient.HR_SERVICE.toString())).build());
            filter.add(new ScanFilter.Builder().setServiceUuid(
                    ParcelUuid.fromString(BlePsFtpUtils.RFC77_PFTP_SERVICE.toString())).build());
            listener.setScanFilters(filter);
        }
    }

    @Override
    public void shutDown() {
        listener.shutDown();
    }

    @Override
    public void cleanup() {
        listener.removeAllSessions();
    }

    @Override
    public void setPolarFilter(boolean enable) {
        if(!enable) {
            listener.setScanPreFilter(null);
        } else {
            listener.setScanPreFilter(filter);
        }
    }

    @Override
    public boolean isFeatureReady(final String deviceId, int feature) {
        try {
            switch (feature) {
                case FEATURE_POLAR_FILE_TRANSFER:
                    return sessionPsFtpClientReady(deviceId) != null;
                case FEATURE_POLAR_SENSOR_STREAMING:
                    return sessionPmdClientReady(deviceId) != null;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    @Override
    public void setApiCallback(PolarBleApiCallbackProvider callback) {
        this.callback = callback;
        callback.blePowerStateChanged(listener.bleActive());
    }

    @Override
    public void setApiLogger(@Nullable PolarBleApiLogger logger) {
        this.logger = logger;
    }

    @Override
    public void setAutomaticReconnection(boolean disable) {
        listener.setAutomaticReconnection(disable);
    }

    @Override
    public Completable setLocalTime(String identifier, Calendar cal) {
        try {
            final BleDeviceSession session = sessionPsFtpClientReady(identifier);
            final BlePsFtpClient client = (BlePsFtpClient) session.fetchClient(BlePsFtpUtils.RFC77_PFTP_SERVICE);
            PftpRequest.PbPFtpSetLocalTimeParams.Builder builder = PftpRequest.PbPFtpSetLocalTimeParams.newBuilder();
            Types.PbDate date = Types.PbDate.newBuilder()
                    .setYear(cal.get(Calendar.YEAR))
                    .setMonth(cal.get(Calendar.MONTH) + 1)
                    .setDay(cal.get(Calendar.DAY_OF_MONTH)).build();
            Types.PbTime time = Types.PbTime.newBuilder()
                    .setHour(cal.get(Calendar.HOUR_OF_DAY))
                    .setMinute(cal.get(Calendar.MINUTE))
                    .setSeconds(cal.get(Calendar.SECOND))
                    .setMillis(cal.get(Calendar.MILLISECOND)).build();
            builder.setDate(date).setTime(time).setTzOffset((int) TimeUnit.MINUTES.convert(cal.get(Calendar.ZONE_OFFSET), TimeUnit.MILLISECONDS));
            return client.query(PftpRequest.PbPFtpQuery.SET_LOCAL_TIME_VALUE,builder.build().toByteArray()).toObservable().ignoreElements();
        } catch (Throwable error){
            return Completable.error(error);
        }
    }

    @Override
    public Single<PolarSensorSetting> requestAccSettings(String identifier) {
        return querySettings(identifier,BlePMDClient.PmdMeasurementType.ACC);
    }

    @Override
    public Single<PolarSensorSetting> requestEcgSettings(String identifier) {
        return querySettings(identifier,BlePMDClient.PmdMeasurementType.ECG);
    }

    @Override
    public Single<PolarSensorSetting> requestPpgSettings(String identifier) {
        return querySettings(identifier,BlePMDClient.PmdMeasurementType.PPG);
    }

    @Override
    public Single<PolarSensorSetting> requestBiozSettings(final String identifier){
        return querySettings(identifier,BlePMDClient.PmdMeasurementType.BIOZ);
    }

    protected Single<PolarSensorSetting> querySettings(final String identifier, final BlePMDClient.PmdMeasurementType type) {
        try {
            final BleDeviceSession session = sessionPmdClientReady(identifier);
            final BlePMDClient client = (BlePMDClient) session.fetchClient(BlePMDClient.PMD_SERVICE);
            return client.querySettings(type).map(new Function<BlePMDClient.PmdSetting, PolarSensorSetting>() {
                @Override
                public PolarSensorSetting apply(BlePMDClient.PmdSetting setting) throws Exception {
                    return new PolarSensorSetting(setting.settings, type);
                }
            });
        } catch (Throwable e){
            return Single.error(e);
        }
    }

    @Override
    public void backgroundEntered() {
        enableAndroidScanFilter();
    }

    @Override
    public void foregroundEntered() {
        listener.setScanFilters(null);
    }

    @Override
    public Completable autoConnectToDevice(final int rssiLimit, final String service, final int timeout, final TimeUnit unit, final String polarDeviceType) {
        final long[] start = {0};
        return Completable.create(new CompletableOnSubscribe() {
            @Override
            public void subscribe(CompletableEmitter emitter) throws Exception {
                if( service == null || service.matches("([0-9a-fA-F]{4})") ) {
                    emitter.onComplete();
                } else {
                    emitter.tryOnError(new PolarInvalidArgument("Invalid service string format"));
                }
            }
        }).andThen(listener.search(false).filter(new Predicate<BleDeviceSession>() {
            @Override
            public boolean test(BleDeviceSession bleDeviceSession) throws Exception {
                if( bleDeviceSession.getMedianRssi() >= rssiLimit &&
                    bleDeviceSession.isConnectableAdvertisement() &&
                    (polarDeviceType == null || polarDeviceType.equals(bleDeviceSession.getPolarDeviceType())) &&
                    (service == null || bleDeviceSession.getAdvertisementContent().containsService(service))    ) {
                        if(start[0] == 0){
                            start[0] = System.currentTimeMillis();
                        }
                        return true;
                }
                return false;
            }
        }).timestamp().takeUntil(new Predicate<Timed<BleDeviceSession>>() {
            @Override
            public boolean test(Timed<BleDeviceSession> bleDeviceSessionTimed) throws Exception {
                long diff =  bleDeviceSessionTimed.time(TimeUnit.MILLISECONDS) - start[0];
                return (diff >= unit.toMillis(timeout));
            }
        }).reduce(new HashSet<BleDeviceSession>(), new BiFunction<Set<BleDeviceSession>, Timed<BleDeviceSession>, Set<BleDeviceSession>>() {
            @Override
            public Set<BleDeviceSession> apply(Set<BleDeviceSession> objects, Timed<BleDeviceSession> bleDeviceSessionTimed) throws Exception {
                objects.add(bleDeviceSessionTimed.value());
                return objects;
            }
        }).doOnSuccess(new Consumer<Set<BleDeviceSession>>() {
            @Override
            public void accept(Set<BleDeviceSession> set) throws Exception {
                List<BleDeviceSession> list = new ArrayList<>(set);
                Collections.sort(list, new Comparator<BleDeviceSession>() {
                    @Override
                    public int compare(BleDeviceSession o1, BleDeviceSession o2) {
                        return o1.getRssi() > o2.getRssi() ? -1 : 1;
                    }
                });
                listener.openSessionDirect(list.get(0));
                log("auto connect search complete");
            }
        }).toObservable().ignoreElements());
    }

    @Override
    public Completable autoConnectToDevice(final int rssiLimit, final String service, final String polarDeviceType) {
        return autoConnectToDevice(rssiLimit, service, 2, TimeUnit.SECONDS, polarDeviceType);
    }

    @Override
    public void connectToDevice(final String identifier) throws PolarInvalidArgument {
        BleDeviceSession session = fetchSession(identifier);
        if( session == null || session.getSessionState() == BleDeviceSession.DeviceSessionState.SESSION_CLOSED ){
            if( connectSubscriptions.containsKey(identifier) ){
                connectSubscriptions.get(identifier).dispose();
                connectSubscriptions.remove(identifier);
            }
            if( session != null ){
                listener.openSessionDirect(session);
            } else {
                connectSubscriptions.put(identifier, listener.search(false).filter(new Predicate<BleDeviceSession>() {
                    @Override
                    public boolean test(BleDeviceSession bleDeviceSession) throws Exception {
                        return identifier.contains(":") ?
                                bleDeviceSession.getAddress().equals(identifier) :
                                bleDeviceSession.getPolarDeviceId().equals(identifier);
                    }
                }).take(1).observeOn(scheduler).subscribe(
                        new Consumer<BleDeviceSession>() {
                            @Override
                            public void accept(BleDeviceSession bleDeviceSession) throws Exception {
                                listener.openSessionDirect(bleDeviceSession);
                            }
                        },
                        new Consumer<Throwable>() {
                            @Override
                            public void accept(Throwable throwable) throws Exception {
                                logError(throwable.getMessage());
                            }
                        },
                        new Action() {
                            @Override
                            public void run() throws Exception {
                                log("connect search complete");
                            }
                        }
                ));
            }
        }
    }

    @Override
    public void disconnectFromDevice(String identifier) throws PolarInvalidArgument {
        BleDeviceSession session = fetchSession(identifier);
        if( session != null ){
            if( session.getSessionState() == BleDeviceSession.DeviceSessionState.SESSION_OPEN ||
                    session.getSessionState() == BleDeviceSession.DeviceSessionState.SESSION_OPENING ||
                    session.getSessionState() == BleDeviceSession.DeviceSessionState.SESSION_OPEN_PARK ) {
                listener.closeSessionDirect(session);
            }
        }
        if (connectSubscriptions.containsKey(identifier)){
            connectSubscriptions.get(identifier).dispose();
            connectSubscriptions.remove(identifier);
        }
    }

    @Override
    public Completable startRecording(String identifier, String exerciseId, RecordingInterval interval, SampleType type) {
        try {
            final BleDeviceSession session = sessionPsFtpClientReady(identifier);
            final BlePsFtpClient client = (BlePsFtpClient) session.fetchClient(BlePsFtpUtils.RFC77_PFTP_SERVICE);
            if(session.getPolarDeviceType().equals("H10")) {
                Types.PbSampleType t = type == SampleType.HR ?
                        Types.PbSampleType.SAMPLE_TYPE_HEART_RATE :
                        Types.PbSampleType.SAMPLE_TYPE_RR_INTERVAL;
                Types.PbDuration duration = Types.PbDuration.newBuilder().setSeconds(interval.getValue()).build();
                PftpRequest.PbPFtpRequestStartRecordingParams params = PftpRequest.PbPFtpRequestStartRecordingParams.newBuilder().
                        setSampleDataIdentifier(exerciseId).setSampleType(t).setRecordingInterval(duration).build();
                return client.query(PftpRequest.PbPFtpQuery.REQUEST_START_RECORDING_VALUE, params.toByteArray()).toObservable().ignoreElements().onErrorResumeNext(new Function<Throwable, CompletableSource>() {
                    @Override
                    public CompletableSource apply(Throwable throwable) throws Exception {
                        return Completable.error(throwable);
                    }
                });
            }
            return Completable.error(new PolarOperationNotSupported());
        } catch (Throwable error){
            return Completable.error(error);
        }
    }

    @Override
    public Completable stopRecording(String identifier) {
        try {
            final BleDeviceSession session = sessionPsFtpClientReady(identifier);
            final BlePsFtpClient client = (BlePsFtpClient) session.fetchClient(BlePsFtpUtils.RFC77_PFTP_SERVICE);
            if(session.getPolarDeviceType().equals("H10")) {
                return client.query(PftpRequest.PbPFtpQuery.REQUEST_STOP_RECORDING_VALUE, null).toObservable().ignoreElements().onErrorResumeNext(new Function<Throwable, CompletableSource>() {
                    @Override
                    public CompletableSource apply(Throwable throwable) throws Exception {
                        return Completable.error(handleError(throwable));
                    }
                });
            }
            return Completable.error(new PolarOperationNotSupported());
        } catch (Throwable error){
            return Completable.error(error);
        }
    }

    @Override
    public Single<Pair<Boolean,String>> requestRecordingStatus(String identifier) {
        try {
            final BleDeviceSession session = sessionPsFtpClientReady(identifier);
            final BlePsFtpClient client = (BlePsFtpClient) session.fetchClient(BlePsFtpUtils.RFC77_PFTP_SERVICE);
            if(session.getPolarDeviceType().equals("H10")) {
                return client.query(PftpRequest.PbPFtpQuery.REQUEST_RECORDING_STATUS_VALUE, null).map(new Function<ByteArrayOutputStream, Pair<Boolean,String>>() {
                    @Override
                    public Pair<Boolean,String> apply(ByteArrayOutputStream byteArrayOutputStream) throws Exception {
                        PftpResponse.PbRequestRecordingStatusResult result = PftpResponse.PbRequestRecordingStatusResult.parseFrom(byteArrayOutputStream.toByteArray());
                        return new Pair<>(result.getRecordingOn(),result.hasSampleDataIdentifier() ? result.getSampleDataIdentifier() : "");
                    }
                }).onErrorResumeNext(new Function<Throwable, SingleSource<? extends Pair<Boolean, String>>>() {
                    @Override
                    public SingleSource<? extends Pair<Boolean, String>> apply(Throwable throwable) throws Exception {
                        return Single.error(handleError(throwable));
                    }
                });
            }
            return Single.error(new PolarOperationNotSupported());
        } catch (Throwable error){
            return Single.error(error);
        }
    }

    @Override
    public Flowable<PolarExerciseEntry> listExercises(String identifier) {
        try{
            final BleDeviceSession session = sessionPsFtpClientReady(identifier);
            final BlePsFtpClient client = (BlePsFtpClient) session.fetchClient(BlePsFtpUtils.RFC77_PFTP_SERVICE);
            switch (session.getPolarDeviceType()) {
                case "OH1":
                    return fetchRecursively(client, "/U/0/", new FetchRecursiveCondition() {
                        @Override
                        public boolean include(String entry) {
                            return entry.matches("^([0-9]{8})(\\/)") ||
                                    entry.matches("^([0-9]{6})(\\/)") ||
                                    entry.equals("E/") ||
                                    entry.equals("SAMPLES.BPB") ||
                                    entry.equals("00/");
                        }
                    }).map(new Function<String, PolarExerciseEntry>() {
                        @Override
                        public PolarExerciseEntry apply(String p) throws Exception {
                            String[] components = p.split("/");
                            SimpleDateFormat format = new SimpleDateFormat("yyyyMMdd HHmmss", Locale.getDefault());
                            Date date = format.parse(components[3] + " " + components[5]);
                            return new PolarExerciseEntry(p, date, components[3] + components[5]);
                        }
                    }).onErrorResumeNext(new Function<Throwable, Publisher<? extends PolarExerciseEntry>>() {
                        @Override
                        public Publisher<? extends PolarExerciseEntry> apply(Throwable throwable) throws Exception {
                            return Flowable.error(handleError(throwable));
                        }
                    });
                case "H10":
                    return fetchRecursively(client, "/", new FetchRecursiveCondition() {
                        @Override
                        public boolean include(String entry) {
                            return entry.endsWith("/") || entry.equals("SAMPLES.BPB");
                        }
                    }).map(new Function<String, PolarExerciseEntry>() {
                        @Override
                        public PolarExerciseEntry apply(String p) throws Exception {
                            String[] components = p.split("/");
                            return new PolarExerciseEntry(p, new Date(), components[1]);
                        }
                    }).onErrorResumeNext(new Function<Throwable, Publisher<? extends PolarExerciseEntry>>() {
                        @Override
                        public Publisher<? extends PolarExerciseEntry> apply(Throwable throwable) throws Exception {
                            return Flowable.error(handleError(throwable));
                        }
                    });
                default:
                    return Flowable.error(new PolarOperationNotSupported());
            }
        } catch (Throwable error){
            return Flowable.error(error);
        }
    }

    @Override
    public Single<PolarExerciseData> fetchExercise(String identifier, PolarExerciseEntry entry) {
        try{
            final BleDeviceSession session = sessionPsFtpClientReady(identifier);
            final BlePsFtpClient client = (BlePsFtpClient) session.fetchClient(BlePsFtpUtils.RFC77_PFTP_SERVICE);
            protocol.PftpRequest.PbPFtpOperation.Builder builder = protocol.PftpRequest.PbPFtpOperation.newBuilder();
            builder.setCommand(PftpRequest.PbPFtpOperation.Command.GET);
            builder.setPath(entry.path);
            if(session.getPolarDeviceType().equals("OH1") || session.getPolarDeviceType().equals("H10")) {
                return client.request(builder.build().toByteArray()).map(new Function<ByteArrayOutputStream, PolarExerciseData>() {
                    @Override
                    public PolarExerciseData apply(ByteArrayOutputStream byteArrayOutputStream) throws Exception {
                        ExerciseSamples.PbExerciseSamples samples = ExerciseSamples.PbExerciseSamples.parseFrom(byteArrayOutputStream.toByteArray());
                        if(samples.hasRrSamples()){
                            return new PolarExerciseData(samples.getRecordingInterval().getSeconds(), samples.getRrSamples().getRrIntervalsList());
                        } else {
                            return new PolarExerciseData(samples.getRecordingInterval().getSeconds(), samples.getHeartRateSamplesList());
                        }
                    }
                }).onErrorResumeNext(new Function<Throwable, SingleSource<? extends PolarExerciseData>>() {
                    @Override
                    public SingleSource<? extends PolarExerciseData> apply(Throwable throwable) throws Exception {
                        return Single.error(handleError(throwable));
                    }
                });
            }
            return Single.error(new PolarOperationNotSupported());
        } catch (Throwable error){
            return Single.error(error);
        }
    }

    @Override
    public Completable removeExercise(String identifier, PolarExerciseEntry entry) {
        try{
            final BleDeviceSession session = sessionPsFtpClientReady(identifier);
            final BlePsFtpClient client = (BlePsFtpClient) session.fetchClient(BlePsFtpUtils.RFC77_PFTP_SERVICE);
            if(session.getPolarDeviceType().equals("OH1")){
                protocol.PftpRequest.PbPFtpOperation.Builder builder = protocol.PftpRequest.PbPFtpOperation.newBuilder();
                builder.setCommand(PftpRequest.PbPFtpOperation.Command.GET);
                final String[] components = entry.path.split("/");
                final String exerciseParent =  "/U/0/" + components[3] + "/E/";
                builder.setPath(exerciseParent);
                return client.request(builder.build().toByteArray()).flatMap(new Function<ByteArrayOutputStream, SingleSource<?>>() {
                    @Override
                    public SingleSource<?> apply(ByteArrayOutputStream byteArrayOutputStream) throws Exception {
                        PftpResponse.PbPFtpDirectory directory = PftpResponse.PbPFtpDirectory.parseFrom(byteArrayOutputStream.toByteArray());
                        protocol.PftpRequest.PbPFtpOperation.Builder removeBuilder = protocol.PftpRequest.PbPFtpOperation.newBuilder();
                        removeBuilder.setCommand(PftpRequest.PbPFtpOperation.Command.REMOVE);
                        if( directory.getEntriesCount() <= 1 ){
                            // remove entire directory
                            removeBuilder.setPath("/U/0/" + components[3] + "/");
                        } else {
                            // remove only exercise
                            removeBuilder.setPath("/U/0/" + components[3] + "/E/" + components[5] + "/");
                        }
                        return client.request(removeBuilder.build().toByteArray());
                    }
                }).toObservable().ignoreElements().onErrorResumeNext(new Function<Throwable, CompletableSource>() {
                    @Override
                    public CompletableSource apply(Throwable throwable) throws Exception {
                        return Completable.error(handleError(throwable));
                    }
                });
            } else if(session.getPolarDeviceType().equals("H10")){
                protocol.PftpRequest.PbPFtpOperation.Builder builder = protocol.PftpRequest.PbPFtpOperation.newBuilder();
                builder.setCommand(PftpRequest.PbPFtpOperation.Command.REMOVE);
                builder.setPath(entry.path);
                return client.request(builder.build().toByteArray()).toObservable().ignoreElements().onErrorResumeNext(new Function<Throwable, CompletableSource>() {
                    @Override
                    public CompletableSource apply(Throwable throwable) throws Exception {
                        return Completable.error(handleError(throwable));
                    }
                });
            }
            return Completable.error(new PolarOperationNotSupported());
        } catch (Throwable error){
            return Completable.error(error);
        }
    }

    @Override
    public Flowable<PolarDeviceInfo> searchForDevice() {
        return listener.search(false).distinct().map(new Function<BleDeviceSession, PolarDeviceInfo>() {
            @Override
            public PolarDeviceInfo apply(BleDeviceSession bleDeviceSession) throws Exception {
                return new PolarDeviceInfo(bleDeviceSession.getPolarDeviceId(),
                                           bleDeviceSession.getAddress(),
                                           bleDeviceSession.getRssi(),
                                           bleDeviceSession.getName(),
                                           bleDeviceSession.isConnectableAdvertisement());
            }
        });
    }

    @Override
    public Flowable<PolarHrBroadcastData> startListenForPolarHrBroadcasts(final Set<String> deviceIds) {
        // set filter to null, NOTE this disables reconnection in background
        return listener.search(false).filter(new Predicate<BleDeviceSession>() {
            @Override
            public boolean test(BleDeviceSession bleDeviceSession) throws Exception {
                return (deviceIds == null || deviceIds.contains(bleDeviceSession.getPolarDeviceId())) &&
                        bleDeviceSession.getAdvertisementContent().getPolarHrAdvertisement().isPresent();
            }
        }).map(new Function<BleDeviceSession, PolarHrBroadcastData>() {
            @Override
            public PolarHrBroadcastData apply(BleDeviceSession bleDeviceSession) throws Exception {
                BlePolarHrAdvertisement advertisement = bleDeviceSession.getBlePolarHrAdvertisement();
                return new PolarHrBroadcastData( new PolarDeviceInfo(bleDeviceSession.getPolarDeviceId(),
                        bleDeviceSession.getAddress(),
                        bleDeviceSession.getRssi(),
                        bleDeviceSession.getName(),
                        bleDeviceSession.isConnectableAdvertisement()),
                        advertisement.getHrForDisplay(),
                        advertisement.getBatteryStatus() != 0);
            }
        });
    }

    @Override
    public Flowable<PolarEcgData> startEcgStreaming(String identifier,
                                                    PolarSensorSetting setting) {
        try {
            final BleDeviceSession session = sessionPmdClientReady(identifier);
            final BlePMDClient client = (BlePMDClient) session.fetchClient(BlePMDClient.PMD_SERVICE);
            return client.startMeasurement(BlePMDClient.PmdMeasurementType.ECG, setting.map2PmdSettings()).andThen(
                client.monitorEcgNotifications(true).map(new Function<BlePMDClient.EcgData, PolarEcgData>() {
                    @Override
                    public PolarEcgData apply(BlePMDClient.EcgData ecgData) throws Exception {
                        List<Integer> samples = new ArrayList<>();
                        for( BlePMDClient.EcgData.EcgSample s : ecgData.ecgSamples ){
                            samples.add(s.microVolts);
                        }
                        return new PolarEcgData(samples,ecgData.timeStamp);
                    }
                }).onErrorResumeNext(new Function<Throwable, Publisher<? extends PolarEcgData>>() {
                    @Override
                    public Publisher<? extends PolarEcgData> apply(Throwable throwable) throws Exception {
                        return Flowable.error(handleError(throwable));
                    }
                }).doFinally(new Action() {
                    @Override
                    public void run() throws Exception {
                        stopPmdStreaming(session,client, BlePMDClient.PmdMeasurementType.ECG);
                    }
                }));
        } catch (Throwable t){
            return Flowable.error(t);
        }
    }

    @Override
    public Flowable<PolarAccelerometerData> startAccStreaming(String identifier,
                                                              PolarSensorSetting setting) {
        try {
            final BleDeviceSession session = sessionPmdClientReady(identifier);
            final BlePMDClient client = (BlePMDClient) session.fetchClient(BlePMDClient.PMD_SERVICE);
            return client.startMeasurement(BlePMDClient.PmdMeasurementType.ACC, setting.map2PmdSettings()).andThen(
                client.monitorAccNotifications(true).map(new Function<BlePMDClient.AccData, PolarAccelerometerData>() {
                @Override
                public PolarAccelerometerData apply(BlePMDClient.AccData accData) throws Exception {
                    List<PolarAccelerometerData.PolarAccelerometerSample> samples = new ArrayList<>();
                    for( BlePMDClient.AccData.AccSample s : accData.accSamples ){
                        samples.add(new PolarAccelerometerData.PolarAccelerometerSample(s.x,s.y,s.z));
                    }
                    return new PolarAccelerometerData(samples,accData.timeStamp);
                }
            }).onErrorResumeNext(new Function<Throwable, Publisher<? extends PolarAccelerometerData>>() {
                @Override
                public Publisher<? extends PolarAccelerometerData> apply(Throwable throwable) throws Exception {
                    return Flowable.error(handleError(throwable));
                }
            }).doFinally(new Action() {
                @Override
                public void run() throws Exception {
                    stopPmdStreaming(session,client, BlePMDClient.PmdMeasurementType.ACC);
                }
            }));
        } catch (Throwable t){
            return Flowable.error(t);
        }
    }

    @Override
    public Flowable<PolarOhrPPGData> startOhrPPGStreaming(String identifier,
                                                          PolarSensorSetting setting) {
        try {
            final BleDeviceSession session = sessionPmdClientReady(identifier);
            final BlePMDClient client = (BlePMDClient) session.fetchClient(BlePMDClient.PMD_SERVICE);
            return client.startMeasurement(BlePMDClient.PmdMeasurementType.PPG, setting.map2PmdSettings()).andThen(
                client.monitorPpgNotifications(true).map(new Function<BlePMDClient.PpgData, PolarOhrPPGData>() {
                    @Override
                    public PolarOhrPPGData apply(BlePMDClient.PpgData ppgData) throws Exception {
                        List<PolarOhrPPGData.PolarOhrPPGSample> samples = new ArrayList<>();
                        for( BlePMDClient.PpgData.PpgSample s : ppgData.ppgSamples ){
                            samples.add(new PolarOhrPPGData.PolarOhrPPGSample(s.ppg0,s.ppg1,s.ppg2,s.ambient,s.ppgDataSamples,s.ambient1,s.status));
                        }
                        return new PolarOhrPPGData(samples,ppgData.timeStamp,ppgData.type);
                    }
                }).doFinally(new Action() {
                    @Override
                    public void run() throws Exception {
                        stopPmdStreaming(session,client, BlePMDClient.PmdMeasurementType.PPG);
                    }
                })).onErrorResumeNext(new Function<Throwable, Publisher<? extends PolarOhrPPGData>>() {
                    @Override
                    public Publisher<? extends PolarOhrPPGData> apply(Throwable throwable) throws Exception {
                        return Flowable.error(handleError(throwable));
                    }
                });
        } catch (Throwable t){
            return Flowable.error(t);
        }
    }

    @Override
    public Flowable<PolarOhrPPIData> startOhrPPIStreaming(String identifier) {
        try {
            final BleDeviceSession session = sessionPmdClientReady(identifier);
            final BlePMDClient client = (BlePMDClient) session.fetchClient(BlePMDClient.PMD_SERVICE);
            return client.startMeasurement(BlePMDClient.PmdMeasurementType.PPI, new BlePMDClient.PmdSetting(new HashMap<BlePMDClient.PmdSetting.PmdSettingType, Integer>())).andThen(
                client.monitorPpiNotifications(true).map(new Function<BlePMDClient.PpiData, PolarOhrPPIData>() {
                    @Override
                    public PolarOhrPPIData apply(BlePMDClient.PpiData ppiData) throws Exception {
                        List<PolarOhrPPIData.PolarOhrPPISample> samples =  new ArrayList<>();
                        for(BlePMDClient.PpiData.PPSample ppSample : ppiData.ppSamples){
                            samples.add(new PolarOhrPPIData.PolarOhrPPISample(ppSample.ppInMs,
                                                                                ppSample.ppErrorEstimate,
                                                                                ppSample.hr,
                                                                        ppSample.blockerBit != 0,
                                                                        ppSample.skinContactStatus != 0,
                                                                        ppSample.skinContactSupported != 0));
                        }
                        return new PolarOhrPPIData(ppiData.timestamp,samples);
                    }
                }).doFinally(new Action() {
                    @Override
                    public void run() throws Exception {
                        stopPmdStreaming(session,client, BlePMDClient.PmdMeasurementType.PPI);
                    }
                })).onErrorResumeNext(new Function<Throwable, Publisher<? extends PolarOhrPPIData>>() {
                    @Override
                    public Publisher<? extends PolarOhrPPIData> apply(Throwable throwable) throws Exception {
                        return Flowable.error(handleError(throwable));
                    }
                });
        } catch (Throwable t){
            return Flowable.error(t);
        }
    }

    @Override
    public Flowable<PolarBiozData> startBiozStreaming(final String identifier, PolarSensorSetting setting){
        try {
            final BleDeviceSession session = sessionPmdClientReady(identifier);
            final BlePMDClient client = (BlePMDClient) session.fetchClient(BlePMDClient.PMD_SERVICE);
            return client.startMeasurement(BlePMDClient.PmdMeasurementType.BIOZ, setting.map2PmdSettings()).andThen(
                    client.monitorBiozNotifications(true).map(new Function<BlePMDClient.BiozData, PolarBiozData>() {
                        @Override
                        public PolarBiozData apply(BlePMDClient.BiozData biozData) throws Exception {
                            return new PolarBiozData(biozData.timeStamp,biozData.samples,biozData.status,biozData.type);
                        }
                    }).doFinally(new Action() {
                        @Override
                        public void run() throws Exception {
                            stopPmdStreaming(session,client, BlePMDClient.PmdMeasurementType.PPG);
                        }
                    })).onErrorResumeNext(new Function<Throwable, Publisher<? extends PolarBiozData>>() {
                @Override
                public Publisher<? extends PolarBiozData> apply(Throwable throwable) throws Exception {
                    return Flowable.error(handleError(throwable));
                }
            });
        } catch (Throwable t){
            return Flowable.error(t);
        }
    }

    protected BleDeviceSession fetchSession(final String identifier) throws PolarInvalidArgument {
        if(identifier.matches("^([0-9A-Fa-f]{2}[:-]){5}([0-9A-Fa-f]{2})$")){
            return sessionByAddress(identifier);
        } else if(identifier.matches("([0-9a-fA-F]){6,8}")) {
            return sessionByDeviceId(identifier);
        }
        throw new PolarInvalidArgument();
    }

    protected BleDeviceSession sessionByAddress(final String address) throws PolarInvalidArgument {
        for ( BleDeviceSession session : listener.deviceSessions() ){
            if( session.getAddress().equals(address) ){
                return session;
            }
        }
        return null;
    }

    protected BleDeviceSession sessionByDeviceId(final String deviceId) throws PolarInvalidArgument {
        for ( BleDeviceSession session : listener.deviceSessions() ){
            if( session.getAdvertisementContent().getPolarDeviceId().equals(deviceId) ){
                return session;
            }
        }
        return null;
    }

    protected BleDeviceSession sessionServiceReady(final String identifier, UUID service) throws Throwable {
        BleDeviceSession session = fetchSession(identifier);
        if(session != null){
            if(session.getSessionState() == BleDeviceSession.DeviceSessionState.SESSION_OPEN) {
                BleGattBase client = session.fetchClient(service);
                if (client.isServiceDiscovered()) {
                    return session;
                }
                throw new PolarServiceNotAvailable();
            }
            throw new PolarDeviceDisconnected();
        }
        throw new PolarDeviceNotFound();
    }

    public BleDeviceSession sessionPmdClientReady(final String identifier) throws Throwable {
        BleDeviceSession session = sessionServiceReady(identifier, BlePMDClient.PMD_SERVICE);
        BlePMDClient client = (BlePMDClient) session.fetchClient(BlePMDClient.PMD_SERVICE);
        final AtomicInteger pair = client.getNotificationAtomicInteger(BlePMDClient.PMD_CP);
        final AtomicInteger pairData = client.getNotificationAtomicInteger(BlePMDClient.PMD_DATA);
        if (pair != null && pairData != null &&
            pair.get() == BleGattBase.ATT_SUCCESS &&
            pairData.get() == BleGattBase.ATT_SUCCESS) {
            return session;
        }
        throw new PolarNotificationNotEnabled();
    }

    protected BleDeviceSession sessionPsFtpClientReady(final String identifier) throws Throwable {
        BleDeviceSession session = sessionServiceReady(identifier, BlePsFtpUtils.RFC77_PFTP_SERVICE);
        BlePsFtpClient client = (BlePsFtpClient) session.fetchClient(BlePsFtpUtils.RFC77_PFTP_SERVICE);
        final AtomicInteger pair = client.getNotificationAtomicInteger(BlePsFtpUtils.RFC77_PFTP_MTU_CHARACTERISTIC);
        if (pair != null && pair.get() == BleGattBase.ATT_SUCCESS ) {
            return session;
        }
        throw new PolarNotificationNotEnabled();
    }

    @SuppressLint("CheckResult")
    protected void stopPmdStreaming(BleDeviceSession session, BlePMDClient client, BlePMDClient.PmdMeasurementType type) {
        if( session.getSessionState() == BleDeviceSession.DeviceSessionState.SESSION_OPEN ){
            // stop streaming
            client.stopMeasurement(type).subscribe(
                new Action() {
                    @Override
                    public void run() throws Exception {

                    }
                },
                new Consumer<Throwable>() {
                    @Override
                    public void accept(Throwable throwable) throws Exception {
                        logError("failed to stop pmd stream: " + throwable.getLocalizedMessage());
                    }
                }
            );
        }
    }

    @SuppressLint("CheckResult")
    protected void setupDevice(final BleDeviceSession session){
        final String deviceId = session.getPolarDeviceId().length() != 0 ? session.getPolarDeviceId() : session.getAddress();
        session.monitorServicesDiscovered(true).observeOn(scheduler).toFlowable().flatMapIterable(
                new Function<List<UUID>, Iterable<UUID>>() {
                    @Override
                    public Iterable<UUID> apply(List<UUID> uuids) throws Exception {
                        return uuids;
                    }
                }
        ).flatMap(new Function<UUID, Publisher<?>>() {
            @Override
            public Publisher<?> apply(UUID uuid) throws Exception {
                if(session.fetchClient(uuid) != null) {
                    if (uuid.equals(BleHrClient.HR_SERVICE)) {
                        if (callback != null) {
                            callback.hrFeatureReady(deviceId);
                        }
                        final BleHrClient client = (BleHrClient) session.fetchClient(BleHrClient.HR_SERVICE);
                        client.observeHrNotifications(true).observeOn(scheduler).subscribe(
                                new Consumer<BleHrClient.HrNotificationData>() {
                                    @Override
                                    public void accept(BleHrClient.HrNotificationData hrNotificationData) throws Exception {
                                        if (callback != null) {
                                            callback.hrNotificationReceived(deviceId,
                                                    new PolarHrData(hrNotificationData.hrValue,
                                                            hrNotificationData.rrs,
                                                            hrNotificationData.sensorContact,
                                                            hrNotificationData.sensorContactSupported,
                                                            hrNotificationData.rrPresent));
                                        }
                                    }
                                },
                                new Consumer<Throwable>() {
                                    @Override
                                    public void accept(Throwable throwable) throws Exception {
                                        logError(throwable.getMessage());
                                    }
                                },
                                new Action() {
                                    @Override
                                    public void run() throws Exception {

                                    }
                                }
                        );
                    } else if (uuid.equals(BleBattClient.BATTERY_SERVICE)) {
                        BleBattClient client = (BleBattClient) session.fetchClient(BleBattClient.BATTERY_SERVICE);
                        return client.waitBatteryLevelUpdate(true).observeOn(scheduler).doOnSuccess(new Consumer<Integer>() {
                            @Override
                            public void accept(Integer integer) throws Exception {
                                if (callback != null) {
                                    callback.batteryLevelReceived(deviceId, integer);
                                }
                            }
                        }).toFlowable();
                    } else if (uuid.equals(BlePMDClient.PMD_SERVICE)) {
                        final BlePMDClient client = (BlePMDClient) session.fetchClient(BlePMDClient.PMD_SERVICE);
                        return client.waitNotificationEnabled(BlePMDClient.PMD_CP, true).
                                concatWith(client.waitNotificationEnabled(BlePMDClient.PMD_DATA, true)).andThen(client.readFeature(true).doOnSuccess(new Consumer<BlePMDClient.PmdFeature>() {
                            @Override
                            public void accept(BlePMDClient.PmdFeature pmdFeature) {
                                if (callback != null) {
                                    if (pmdFeature.ecgSupported) {
                                        callback.ecgFeatureReady(deviceId);
                                    }
                                    if (pmdFeature.accSupported) {
                                        callback.accelerometerFeatureReady(deviceId);
                                    }
                                    if (pmdFeature.ppgSupported) {
                                        callback.ppgFeatureReady(deviceId);
                                    }
                                    if (pmdFeature.ppiSupported) {
                                        callback.ppiFeatureReady(deviceId);
                                    }
                                    if (pmdFeature.bioZSupported) {
                                        callback.biozFeatureReady(deviceId);
                                    }
                                }
                            }
                        })).toFlowable();
                    } else if (uuid.equals(BleDisClient.DIS_SERVICE)) {
                        BleDisClient client = (BleDisClient) session.fetchClient(BleDisClient.DIS_SERVICE);
                        return client.observeDisInfo(true).observeOn(scheduler).doOnNext(new Consumer<Pair<UUID, String>>() {
                            @Override
                            public void accept(Pair<UUID, String> pair) {
                                if (callback != null) {
                                    callback.disInformationReceived(deviceId, pair.first , pair.second);
                                }
                            }
                        });
                    } else if (uuid.equals(BlePsFtpUtils.RFC77_PFTP_SERVICE)) {
                        BlePsFtpClient client = (BlePsFtpClient) session.fetchClient(BlePsFtpUtils.RFC77_PFTP_SERVICE);
                        return client.waitPsFtpClientReady(true).observeOn(scheduler).doOnComplete(new Action() {
                            @Override
                            public void run() throws Exception {
                                if (callback != null &&
                                        (session.getPolarDeviceType().equals("OH1") || session.getPolarDeviceType().equals("H10"))) {
                                    callback.polarFtpFeatureReady(deviceId);
                                }
                            }
                        }).toFlowable();
                    }
                }
                return Flowable.empty();
            }
        }).subscribe(
                new Consumer<Object>() {
                    @Override
                    public void accept(Object o) throws Exception {

                    }
                },
                new Consumer<Throwable>() {
                    @Override
                    public void accept(Throwable throwable) throws Exception {
                        logError(throwable.getMessage());
                    }
                },
                new Action() {
                    @Override
                    public void run() throws Exception {
                        log("complete");
                    }
                });
    }

    protected Exception handleError(Throwable throwable) {
        if( throwable instanceof BleDisconnected ){
            return new PolarDeviceDisconnected();
        } else {
            return new Exception("Unknown Error: " + throwable.getLocalizedMessage());
        }
    }

    interface FetchRecursiveCondition {
        boolean include(String entry);
    }

    protected Flowable<String> fetchRecursively(final BlePsFtpClient client, final String path, final FetchRecursiveCondition condition) {
        protocol.PftpRequest.PbPFtpOperation.Builder builder = protocol.PftpRequest.PbPFtpOperation.newBuilder();
        builder.setCommand(PftpRequest.PbPFtpOperation.Command.GET);
        builder.setPath(path);
        return client.request(builder.build().toByteArray()).toFlowable().flatMap(new Function<ByteArrayOutputStream, Publisher<String>>() {
            @Override
            public Publisher<String> apply(ByteArrayOutputStream byteArrayOutputStream) throws Exception {
                PftpResponse.PbPFtpDirectory dir = PftpResponse.PbPFtpDirectory.parseFrom(byteArrayOutputStream.toByteArray());
                Set<String> entrys = new HashSet<>();
                for( int i=0; i < dir.getEntriesCount(); ++i ){
                    PftpResponse.PbPFtpEntry entry = dir.getEntries(i);
                    if( condition.include(entry.getName()) ){
                        BleUtils.validate(entrys.add(path + entry.getName()),"duplicate entry");
                    }
                }
                if(entrys.size()!=0) {
                    return Flowable.fromIterable(entrys).flatMap(new Function<String, Publisher<String>>() {
                        @Override
                        public Publisher<String> apply(String s) {
                            if (s.endsWith("/")) {
                                return fetchRecursively(client, s, condition);
                            } else {
                                return Flowable.just(s);
                            }
                        }
                    });
                }
                return Flowable.empty();
            }
        });
    }

    protected void log(final String message) {
        if(logger != null){
            logger.message("" + message);
        }
    }

    protected void logError(final String message) {
        if(logger != null){
            logger.message("Error: "+message);
        }
    }
}