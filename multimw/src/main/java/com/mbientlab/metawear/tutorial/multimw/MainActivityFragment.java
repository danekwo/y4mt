/*
 * Copyright 2015 MbientLab Inc. All rights reserved.
 *
 * IMPORTANT: Your use of this Software is limited to those specific rights
 * granted under the terms of a software license agreement between the user who
 * downloaded the software, his/her employer (which must be your employer) and
 * MbientLab Inc, (the "License").  You may not use this Software unless you
 * agree to abide by the terms of the License which can be found at
 * www.mbientlab.com/terms . The License limits your use, and you acknowledge,
 * that the  Software may not be modified, copied or distributed and can be used
 * solely and exclusively in conjunction with a MbientLab Inc, product.  Other
 * than for the foregoing purpose, you may not use, reproduce, copy, prepare
 * derivative works of, modify, distribute, perform, display or sell this
 * Software and/or its documentation for any purpose.
 *
 * YOU FURTHER ACKNOWLEDGE AND AGREE THAT THE SOFTWARE AND DOCUMENTATION ARE
 * PROVIDED "AS IS" WITHOUT WARRANTY OF ANY KIND, EITHER EXPRESS OR IMPLIED,
 * INCLUDING WITHOUT LIMITATION, ANY WARRANTY OF MERCHANTABILITY, TITLE,
 * NON-INFRINGEMENT AND FITNESS FOR A PARTICULAR PURPOSE. IN NO EVENT SHALL
 * MBIENTLAB OR ITS LICENSORS BE LIABLE OR OBLIGATED UNDER CONTRACT, NEGLIGENCE,
 * STRICT LIABILITY, CONTRIBUTION, BREACH OF WARRANTY, OR OTHER LEGAL EQUITABLE
 * THEORY ANY DIRECT OR INDIRECT DAMAGES OR EXPENSES INCLUDING BUT NOT LIMITED
 * TO ANY INCIDENTAL, SPECIAL, INDIRECT, PUNITIVE OR CONSEQUENTIAL DAMAGES, LOST
 * PROFITS OR LOST DATA, COST OF PROCUREMENT OF SUBSTITUTE GOODS, TECHNOLOGY,
 * SERVICES, OR ANY CLAIMS BY THIRD PARTIES (INCLUDING BUT NOT LIMITED TO ANY
 * DEFENSE THEREOF), OR OTHER SIMILAR COSTS.
 *
 * Should you have any questions regarding your right to use this Software,
 * contact MbientLab Inc, at www.mbientlab.com.
 */

package com.mbientlab.metawear.tutorial.multimw;

import android.app.Activity;
import android.bluetooth.BluetoothDevice;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.support.annotation.Nullable;
import android.support.design.widget.Snackbar;
import android.support.v4.app.Fragment;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ListView;
import android.widget.Toolbar;

import com.mbientlab.metawear.AsyncDataProducer;
import com.mbientlab.metawear.MetaWearBoard;
import com.mbientlab.metawear.Route;
import com.mbientlab.metawear.Subscriber;
import com.mbientlab.metawear.android.BtleService;
import com.mbientlab.metawear.data.Acceleration;
import com.mbientlab.metawear.data.SensorOrientation;
import com.mbientlab.metawear.module.Accelerometer;
import com.mbientlab.metawear.module.AccelerometerBmi160;
import com.mbientlab.metawear.module.Debug;
import com.mbientlab.metawear.module.Haptic;
import com.mbientlab.metawear.module.Switch;
import com.mbientlab.metawear.module.Temperature;

import java.util.HashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import bolts.Capture;
import bolts.Continuation;

/**
 * A placeholder fragment containing a simple view.
 */
public class MainActivityFragment extends Fragment implements ServiceConnection {
    private final HashMap<DeviceState, MetaWearBoard> stateToBoards;
    private BtleService.LocalBinder binder;

    private ConnectedDevicesAdapter connectedDevices= null;

    public MainActivityFragment() {
        stateToBoards = new HashMap<>();
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Activity owner= getActivity();
        owner.getApplicationContext().bindService(new Intent(owner, BtleService.class), this, Context.BIND_AUTO_CREATE);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        getActivity().getApplicationContext().unbindService(this);
    }


    public void addNewDevice(BluetoothDevice btDevice) {
        final DeviceState newDeviceState= new DeviceState(btDevice);
        final MetaWearBoard newBoard= binder.getMetaWearBoard(btDevice);

        newDeviceState.connecting= true;
        connectedDevices.add(newDeviceState);
        stateToBoards.put(newDeviceState, newBoard);

        final Capture<AsyncDataProducer> orientCapture = new Capture<>();
        final Capture<AccelerometerBmi160> accelerometerBmi160Capture = new Capture<>();
        final Capture<AccelerometerBmi160.StepDetectorDataProducer> stepCapture = new Capture<>();
        final Capture<AsyncDataProducer> accelDataCapture = new Capture<>();

        AtomicInteger stepCount = new AtomicInteger(0);
        AtomicReference<Boolean> twoStep = new AtomicReference<>(false);

        newBoard.onUnexpectedDisconnect(status -> getActivity().runOnUiThread(() -> connectedDevices.remove(newDeviceState)));
        newBoard.connectAsync(
        //!  accel, step and orientation handling
        ).onSuccessTask(task -> {
            getActivity().runOnUiThread(() -> {
                newDeviceState.connecting= false;
                connectedDevices.notifyDataSetChanged();
            });

            final AccelerometerBmi160 accelerometer = newBoard.getModule(AccelerometerBmi160.class);
            final AsyncDataProducer orientation = accelerometer.orientation();
            final AsyncDataProducer accel = accelerometer.packedAcceleration();
            final AccelerometerBmi160.StepDetectorDataProducer stepDetector = accelerometer.stepDetector();

            //* ODR? RANGE? use –>  accelerometer.getOdr(); accelerometer.getRange();
            //TODO configure accel

            //? if I place the config edit here, will it "update" everytime a step is detected?
            //TODO look into checking whether config parameters actually went through
            stepDetector.configure().mode(AccelerometerBmi160.StepDetectorMode.ROBUST).commit();

            accelerometerBmi160Capture.set(accelerometer);
            orientCapture.set(orientation);
            stepCapture.set(stepDetector);
            accelDataCapture.set(accel);

            accel.addRouteAsync(source -> source.multicast()
                .to().stream((data, env) -> {
                    getActivity().runOnUiThread(() -> {
                        newDeviceState.deviceAccel = "Accel:" + data.value(Acceleration.class).toString();
                        connectedDevices.notifyDataSetChanged();
                    });
                })/* .to().stream() */);

            stepDetector.addRouteAsync(source -> source.stream((data, env) -> {
                getActivity().runOnUiThread(() -> {
                    /**NOTE - Step detection algorithm
                    looks like the built-in step detection algorithm detects both feet touchdown and liftoff as steps.
                    so as a quick and dirty fix, I'll flip between bool states to effectively only register half the steps,
                    to more accurately mirror steps irl with each leg
                    this was tested with the device at ankle level, it may perform differently anywhere else.
                    */
                    if (twoStep.get()) {
                        twoStep.set(false);
                        stepCount.getAndIncrement();
                        newDeviceState.deviceSteps = "Steps:" + stepCount;
                        connectedDevices.notifyDataSetChanged();

                        newBoard.getModule(Haptic.class).startMotor((short) 400); //* step –> vibrate
                    }
                    else{twoStep.set(true);}
                });
            }));

            orientation.addRouteAsync(source -> source.stream((data, env) -> getActivity().runOnUiThread(() -> {
                newDeviceState.deviceOrientation = data.value(SensorOrientation.class).toString();
                connectedDevices.notifyDataSetChanged();
            })));

            return null;
        }
        //! button press handling
        ).onSuccessTask(task -> newBoard.getModule(Switch.class).state().addRouteAsync(source -> source.stream((Subscriber) (data, env) -> {
            getActivity().runOnUiThread(() -> {
                newDeviceState.pressed = data.value(Boolean.class);
                connectedDevices.notifyDataSetChanged();
            });
            //* example: Turn blue led on when button is pressed
//            Led led = newBoard.getModule(Led.class);
//            if (data.value(Boolean.class)){
//                led.editPattern(Led.Color.BLUE, Led.PatternPreset.SOLID).commit();
//                led.play();
//            } else{
//                led.stop(true);
//            }

        }))).continueWith((Continuation<Route, Void>) task -> {
            if (task.isFaulted()) {
                if (!newBoard.isConnected()) {
                    getActivity().runOnUiThread(() -> connectedDevices.remove(newDeviceState));
                } else {
                    Snackbar.make(getActivity().findViewById(R.id.activity_main_layout), task.getError().getLocalizedMessage(), Snackbar.LENGTH_SHORT).show();
                    newBoard.tearDown();
                    newBoard.disconnectAsync().continueWith((Continuation<Void, Void>) task1 -> {
                        connectedDevices.remove(newDeviceState);
                        return null;
                    });
                }
            } else {
                orientCapture.get().start();
                accelerometerBmi160Capture.get().start();
                stepCapture.get().start();
                accelDataCapture.get().start();
            }
            return null;
        });
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        connectedDevices= new ConnectedDevicesAdapter(getActivity(), R.id.metawear_status_layout);
        connectedDevices.setNotifyOnChange(true);
        setRetainInstance(true);
        return inflater.inflate(R.layout.fragment_main, container, false);
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        ListView connectedDevicesView= view.findViewById(R.id.connected_devices);
        connectedDevicesView.setAdapter(connectedDevices);
        connectedDevicesView.setOnItemLongClickListener((parent, view1, position, id) -> {
            DeviceState current= connectedDevices.getItem(position);
            final MetaWearBoard selectedBoard= stateToBoards.get(current);

            Accelerometer accelerometer = selectedBoard.getModule(Accelerometer.class);
            accelerometer.stop();
            ((AccelerometerBmi160) accelerometer).orientation().stop();
            ((AccelerometerBmi160) accelerometer).stepDetector().stop();

            selectedBoard.tearDown();
            selectedBoard.getModule(Debug.class).disconnectAsync();

            connectedDevices.remove(current);
            return false;
        });
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder service) {
        binder= (BtleService.LocalBinder) service;
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {

    }
}
