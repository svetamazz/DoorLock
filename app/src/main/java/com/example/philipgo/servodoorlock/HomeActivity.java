package com.example.philipgo.servodoorlock;

import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;
import android.annotation.SuppressLint;
import android.os.Build;
import android.os.Bundle;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Intent;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import android.os.Handler;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.auth.FirebaseAuth;

@SuppressLint("Registered")
public class HomeActivity extends AppCompatActivity {

    private final String DEVICE_ADDRESS = "98:D3:31:F9:6F:D1"; //MAC Address of Bluetooth Module
    private final UUID PORT_UUID = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb");

    private FirebaseFirestore db = FirebaseFirestore.getInstance();

    private BluetoothDevice device;
    private BluetoothSocket socket;

    private OutputStream outputStream;
    private InputStream inputStream;

    String lockState;

    Thread thread;
    byte buffer[];

    boolean stopThread;
    boolean connected = false;
    String command;

    Button lock_state_btn, bluetooth_connect_btn, logout_btn;
    TextView lock_state_text;
    ImageView lock_state_img;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        lock_state_btn = (Button) findViewById(R.id.lock_state_btn);
        bluetooth_connect_btn = (Button) findViewById(R.id.bluetooth_connect_btn);
        logout_btn =  (Button) findViewById(R.id.logout_btn);

        lock_state_text = (TextView) findViewById(R.id.lock_state_text);
        lock_state_img = (ImageView) findViewById(R.id.lock_state_img);

        logout_btn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v){
                FirebaseAuth.getInstance().signOut();
                Intent intToMain = new Intent(HomeActivity.this, MainActivity.class);
                startActivity(intToMain);
            }
        });

        bluetooth_connect_btn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v){
                if(BTinit())
                {
                    if(BTconnect()) {
                        bluetooth_connect_btn.setText("Disconnect from lock");
                        bluetooth_connect_btn.setBackgroundColor(getResources().getColor(R.color.green));
                        beginListenForData();
                       command = "3";
                        try {
                            outputStream.write(command.getBytes());
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
                    else{
                        bluetooth_connect_btn.setText("Connect to lock");
                        bluetooth_connect_btn.setBackgroundColor(getResources().getColor(R.color.red));
                        Toast.makeText(getApplicationContext(), "NOT connected to the lock", Toast.LENGTH_SHORT).show();
                    }
                }
                else{
                    Toast.makeText(getApplicationContext(), "Please pair the device first", Toast.LENGTH_SHORT).show();
                }
            }
        });


        lock_state_btn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v){

                if(!connected)
                {
                    Toast.makeText(getApplicationContext(), "Connect to the door lock first", Toast.LENGTH_SHORT).show();
                }
                else
                {
                    command = "1";
                    try
                    {
                        outputStream.write(command.getBytes());
                    }
                    catch (IOException e)
                    {
                        e.printStackTrace();
                    }
                }
            }
        });
    }

    void writeActionToDB()
    {
        Map<String, Object> note = new HashMap<>();
        note.put("UserEmail", MainActivity.userEmail);
        note.put("Action", lockState);
        note.put("Time", new Date());

        Date dNow = new Date();
        SimpleDateFormat ft = new SimpleDateFormat("yyMMddhhmmssMs", Locale.ENGLISH);
        String uniqueId = ft.format(dNow);

        db.collection(MainActivity.userEmail).document(uniqueId).set(note);
    }

    void beginListenForData() // begins listening for any incoming data from the Arduino
    {
        final Handler handler = new Handler();
        stopThread = false;
        buffer = new byte[1024];

        Thread thread = new Thread(new Runnable()
        {
            public void run()
            {
                while(!Thread.currentThread().isInterrupted() && !stopThread)
                {
                    try
                    {
                        int byteCount = inputStream.available();

                        if(byteCount > 0)
                        {
                            byte[] rawBytes = new byte[byteCount];
                            inputStream.read(rawBytes);
                            final String string = new String(rawBytes, "UTF-8");

                            handler.post(new Runnable()
                            {
                                @RequiresApi(api = Build.VERSION_CODES.O)
                                public void run()
                                {
                                    if(string.equals("0"))
                                    {
                                        lockState="locked";
                                        lock_state_text.setText("Lock State: LOCKED"); // Changes the lock state text
                                        lock_state_btn.setText("Unlock door");
                                        lock_state_btn.setBackgroundColor(getResources().getColor(R.color.red));
                                        lock_state_img.setImageResource(R.drawable.locked_icon); //Changes the lock state icon
                                    }
                                    else if(string.equals("1"))
                                    {
                                        lockState="unlocked";
                                        lock_state_btn.setText("Lock door");
                                        lock_state_text.setText("Lock State: UNLOCKED");
                                        lock_state_btn.setBackgroundColor(getResources().getColor(R.color.green));
                                        lock_state_img.setImageResource(R.drawable.unlocked_icon);
                                    }
                                    if(command.equals("1"))
                                    {
                                        writeActionToDB();
                                    }
                                }
                            });
                        }
                    }
                    catch (IOException ex)
                    {
                        stopThread = true;
                    }
                }
            }
        });

        thread.start();
    }

    //Initializes bluetooth module
    public boolean BTinit()
    {
        boolean found = false;

        BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

        if(bluetoothAdapter == null) //Checks if the device supports bluetooth
        {
            Toast.makeText(getApplicationContext(), "Device doesn't support bluetooth", Toast.LENGTH_SHORT).show();
        }

        if(!bluetoothAdapter.isEnabled()) //Checks if bluetooth is enabled. If not, the program will ask permission from the user to enable it
        {
            Intent enableAdapter = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(enableAdapter,0);

            try
            {
                Thread.sleep(1000);
            }
            catch(InterruptedException e)
            {
                e.printStackTrace();
            }
        }

        Set<BluetoothDevice> bondedDevices = bluetoothAdapter.getBondedDevices();

        if(bondedDevices.isEmpty()) //Checks for paired bluetooth devices
        {
            Toast.makeText(getApplicationContext(), "Please pair the device first", Toast.LENGTH_SHORT).show();
        }
        else
        {
            for(BluetoothDevice iterator : bondedDevices)
            {
                if(iterator.getAddress().equals(DEVICE_ADDRESS))
                {
                    device = iterator;
                    return true;
                }
            }
        }

        return found;
    }

    public boolean BTconnect()
    {
        try
        {
            socket = device.createRfcommSocketToServiceRecord(PORT_UUID); //Creates a socket to handle the outgoing connection
            socket.connect();

            Toast.makeText(getApplicationContext(),
                    "Connection to bluetooth device successful", Toast.LENGTH_LONG).show();
            connected = true;
        }
        catch(IOException e)
        {
            e.printStackTrace();
            connected = false;
        }

        if(connected)
        {
            try
            {
                outputStream = socket.getOutputStream(); //gets the output stream of the socket
            }
            catch(IOException e)
            {
                e.printStackTrace();
            }

            try
            {
                inputStream = socket.getInputStream(); //gets the input stream of the socket
            }
            catch (IOException e)
            {
                e.printStackTrace();
            }
        }

        return connected;
    }

    @Override
    protected void onStart()
    {
        super.onStart();
    }
}


















