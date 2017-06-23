package com.polidea.rxandroidble.sample.example4_characteristic.advanced;


import static android.bluetooth.BluetoothGattCharacteristic.PROPERTY_INDICATE;
import static android.bluetooth.BluetoothGattCharacteristic.PROPERTY_NOTIFY;

import android.bluetooth.BluetoothGattCharacteristic;
import android.support.annotation.NonNull;
import com.polidea.rxandroidble.NotificationSetupMode;
import com.polidea.rxandroidble.RxBleDevice;
import java.util.UUID;
import rx.Observable;

/**
 * Presenter class for {@link AdvancedCharacteristicOperationExampleActivity}. Prepares the logic for the activity using passed
 * {@link Observable}s. Contains only static methods to show a purely reactive (stateless) approach.
 * <p>
 * The contract of the class is that it subscribes to the passed Observables only if a specific functionality is possible to use.
 */
final class Presenter {

    @SuppressWarnings("WeakerAccess")
    static UUID CLIENT_CHARACTERISTIC_CONFIG_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private Presenter() {} // not instantiable

    static Observable<PresenterEvent> prepareActivityLogic(
            final RxBleDevice rxBleDevice,
            final UUID characteristicUuid,
            final Observable<Boolean> connectClicks,
            final Observable<Boolean> connectingClicks, // used to disconnect the device even before connection is established
            final Observable<Boolean> disconnectClicks,
            final Observable<Boolean> readClicks,
            final Observable<byte[]> writeClicks,
            final Observable<Boolean> enableNotifyClicks,
            final Observable<Boolean> enablingNotifyClicks, // used to disable notifications before they were enabled (but after enable click)
            final Observable<Boolean> disableNotifyClicks,
            final Observable<Boolean> enableIndicateClicks,
            final Observable<Boolean> enablingIndicateClicks, // used to disable indications before they were enabled (but after enable click)
            final Observable<Boolean> disableIndicateClicks
    ) {

        return connectClicks.take(1) // subscribe to connectClicks and take one (unsubscribe after)
                .flatMap(connectClick -> rxBleDevice.establishConnection(false) // on click start connecting
                        .flatMap(
                                connection -> connection
                                        .discoverServices() // when connected discover services
                                        .flatMap(services -> services.getCharacteristic(characteristicUuid)), // and get characteristic
                                (connection, characteristic) -> { // when we have connection and characteristic

                                    final Observable<PresenterEvent> readObservable =
                                            !hasProperty(characteristic, BluetoothGattCharacteristic.PROPERTY_READ)
                                                    ? Observable.empty() // if the characteristic is not readable return an empty (dummy) observable
                                                    : readClicks // else use the readClicks observable from the activity
                                                    .flatMap(ignoredClick -> connection.readCharacteristic(characteristic)) // every click is requesting a read operation from the peripheral
                                                    .compose(transformToPresenterEvent(Type.READ)) // convenience method to wrap reads
                                                    .compose(repeatAfterCompleted()); // if this observable will complete (i.e. error happens) then repeat it

                                    final Observable<PresenterEvent> writeObservable = // basically the same logic as in the reads
                                            !hasProperty(characteristic, BluetoothGattCharacteristic.PROPERTY_WRITE)
                                                    ? Observable.empty()
                                                    : writeClicks // with exception that clicks emit byte[] to write
                                                    .flatMap(bytes -> connection.writeCharacteristic(characteristic, bytes))
                                                    .compose(transformToPresenterEvent(Type.WRITE));

                                    final NotificationSetupMode notificationSetupMode = // checking if characteristic will potentially need a compatibility mode notifications
                                            characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID) == null
                                                    ? NotificationSetupMode.COMPAT
                                                    : NotificationSetupMode.DEFAULT;
                                    /*
                                     * wrapping observables for notifications and indications so they will emit FALSE and TRUE respectively.
                                     * this is needed because only one of them may be active at the same time and we need to differentiate the clicks
                                     */
                                    final Observable<Boolean> enableNotifyClicksObservable = !hasProperty(characteristic, PROPERTY_NOTIFY)
                                            /*
                                             * if property for notifications is not available return Observable.never() dummy observable.
                                             * Observable.never() is needed because of the Observable.amb() below which repeats
                                             * the behaviour of Observable that first emits or terminates and it will be checking both
                                             * notifyClicks and indicateClicks
                                             */
                                            ? Observable.never()
                                            : enableNotifyClicks.take(1).map(aBoolean -> Boolean.FALSE); // only the first click to enableNotifyClicks is taken to account
                                    final Observable<Boolean> enableIndicateClicksObservable = !hasProperty(characteristic, PROPERTY_INDICATE)
                                            ? Observable.never()
                                            : enableIndicateClicks.take(1).map(aBoolean -> Boolean.TRUE);

                                    // checking which notify or indicate will be clicked first the other is unsubscribed on click
                                    final Observable<PresenterEvent> notifyAndIndicateObservable = Observable.amb(
                                            enableNotifyClicksObservable,
                                            enableIndicateClicksObservable
                                    )
                                            .flatMap(isIndication -> {
                                                if (isIndication) { // if indication was clicked
                                                    return connection
                                                            .setupIndication(characteristicUuid, notificationSetupMode) // we setup indications
                                                            .compose(takeUntil(enablingIndicateClicks, disableIndicateClicks)) // use a convenience transformer for tearing down the notifications
                                                            .compose(transformToNotificationPresenterEvent(Type.INDICATE)); // and wrap the emissions with a convenience function
                                                } else { // if notification was clicked
                                                    return connection
                                                            .setupNotification(characteristicUuid, notificationSetupMode)
                                                            .compose(takeUntil(enablingNotifyClicks, disableNotifyClicks))
                                                            .compose(transformToNotificationPresenterEvent(Type.NOTIFY));
                                                }
                                            })
                                            .compose(repeatAfterCompleted()) // whenever the notification or indication is finished (by the user or an error) repeat it
                                            .startWith(new CompatibilityModeEvent( // at the beginning inform the activity about whether compat mode is being used
                                                    hasProperty(characteristic, PROPERTY_NOTIFY | PROPERTY_INDICATE)
                                                            && notificationSetupMode == NotificationSetupMode.COMPAT
                                            ));

                                    return Observable.merge( // merge all events from reads, writes, notifications and indications
                                            readObservable,
                                            writeObservable,
                                            notifyAndIndicateObservable
                                    )
                                            .startWith(new InfoEvent("Hey, connection has been established!")); // start by informing the Activity that connection is established
                                }
                        )
                        .flatMap(presenterEventObservable -> presenterEventObservable) // flatMap the observable to itself to get the PresenterEvents
                        .compose(takeUntil(connectingClicks, disconnectClicks)) // convenience transformer to close the connection
                        .onErrorReturn(throwable -> new InfoEvent("Connection error: " + throwable)) // in case of a connection error inform the activity
                )
                .compose(repeatAfterCompleted()); // if the the above will complete - start from the beginning
    }

    @SuppressWarnings("WeakerAccess")
    static boolean hasProperty(BluetoothGattCharacteristic characteristic, int property) {
        return (characteristic.getProperties() & property) > 0;
    }

    /**
     * A convenience function creating a transformer that will use two observables for completing the returned observable (and
     * un-subscribing from the passed observable) beforeEmission will be used to complete the passed observable before it's first
     * emission and afterEmission will be used to do the same after the first emission
     *
     * @param beforeEmission the observable that will control completing the returned observable before it's first emission
     * @param afterEmission the observable that will control completing the returned observable after it's first emission
     * @param <T> the type of the passed observable
     * @return the observable
     */
    @SuppressWarnings("WeakerAccess")
    @NonNull
    static <T> Observable.Transformer<T, T> takeUntil(Observable<?> beforeEmission, Observable<?> afterEmission) {
        //noinspection unchecked -> this would be not needed in case of inline reified kotlin function
        return observable -> observable.publish(publishedObservable ->
                Observable.amb(
                        publishedObservable,
                        ((Observable<T>) beforeEmission.take(1).ignoreElements())
                )
                        .takeUntil(publishedObservable
                                .take(1)
                                .toCompletable()
                                .andThen(afterEmission)
                        )
        );
    }

    /**
     * A convenience function creating a transformer that will wrap the emissions in either {@link ResultEvent} or {@link ErrorEvent}
     * with a given {@link Type}
     *
     * @param type the type to wrap with
     * @return transformer that will emit an observable that will be emitting ResultEvent or ErrorEvent with a given type
     */
    @SuppressWarnings("WeakerAccess")
    @NonNull
    static Observable.Transformer<byte[], PresenterEvent> transformToPresenterEvent(Type type) {
        return observable -> observable.map(writtenBytes -> ((PresenterEvent) new ResultEvent(writtenBytes, type)))
                .onErrorReturn(throwable -> new ErrorEvent(throwable, type));
    }

    /**
     * A convenience function creating a transformer that will wrap the emissions in either {@link ResultEvent} or {@link ErrorEvent}
     * with a given {@link Type} for notification type {@link Observable} (Observable<Observable<byte[]>>)
     *
     * @param type the type to wrap with
     * @return the transformer
     */
    @SuppressWarnings("WeakerAccess")
    @NonNull
    static Observable.Transformer<Observable<byte[]>, PresenterEvent> transformToNotificationPresenterEvent(Type type) {
        return observableObservable -> observableObservable
                .flatMap(observable -> observable
                        .map(bytes -> ((PresenterEvent) new ResultEvent(bytes, type)))
                )
                .onErrorReturn(throwable -> new ErrorEvent(throwable, type))
                /*
                 * since there is a flatMap above the returned Observable will not finish until the last observable returned from it will
                 * not finish. Thing is that when the original observableObservable finishes the emitted observable should finish as well
                 * as it will not emit any more values.
                 * TODO: [DS] 23.06.2017 this should be done by the library itself
                 */
                .takeUntil(observableObservable.ignoreElements());
    }

    /**
     * A convenience function creating a transformer that will repeat the source observable whenever it will complete
     *
     * @param <T> the type of the transformed observable
     * @return transformer that will emit observable that will never complete (source will be subscribed again)
     */
    @SuppressWarnings("WeakerAccess")
    @NonNull
    static <T> Observable.Transformer<T, T> repeatAfterCompleted() {
        return observable -> observable.repeatWhen(completedNotification -> completedNotification);
    }
}
