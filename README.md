# android_uevent_project
  If need to add the mount/unmount device function as hotplug, the switch device driver is easy to control between HAL and Kernel.
  There is two case both Switch device driver and MISC device driver.
  - Switch device driver
    1) SYSFS Path : /sys/devices/virtual/switch/oem_switch_drider
    2) Usage:
      - Include the header file 
        #include <linux/switch.h> 
      - Define the flag 
        static struct switch_dev oem_device_mount; 
      - Register
       static int OEM_device_probe(struct platform_device *pdev)
           oem_device_mount.name = "oem_mount";
           oem_device_mount.index = 0;
	         oem_device_mount.state = 1;
           ret = switch_dev_register(&oem_device_mount);
           if (ret) {
                printk("%s: switch_dev_register error (%d)\n", __func__, ret);
                return 1;
            }
	          switch_set_state((struct switch_dev *)&oem_device_mount, 1);
      - UnRegister
        static int OEM_device_remove(struct platform_device *pdev)
           switch_dev_unregister(&oem_device_mount);
      - Set Flag
        static int OEM_device_mount(int mount) 
      	    switch_set_state((struct switch_dev *)&cam_r_insert_data, mount);

  - To control between HAL to frameworks.
    : There is useful for Uevent function by android.
    1) Usage :
       - Use the UEventObserver
         import android.os.UEventObserver; 
       - Add the Uevent Observer
         private final UEventObserver mObserver = new UEventObserver() {
            @Override
            public void onUEvent(UEventObserver.UEvent event) {
               try {
                  synchronized (mLock) {
                       String switch_name = event.get("SWITCH_NAME").trim();
                        if (switch_name.equals("oem_mount")) {
                                String data =  event.get("SWITCH_STATE").trim();
                 	               update(data);



