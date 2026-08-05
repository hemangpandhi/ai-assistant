package com.tcs.vehicleassistant.ui

import android.car.biometrics.ICarBiometricService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.tcs.vehicleassistant.R
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread
import android.os.Handler
import android.os.Looper

class AmazonShoppingActivity : AppCompatActivity(), SurfaceHolder.Callback {

    private lateinit var rvProducts: androidx.recyclerview.widget.RecyclerView
    private lateinit var tvStatus: TextView
    private lateinit var svBiometricPreview: SurfaceView
    private lateinit var tvBiometricStatus: TextView

    private var biometricService: ICarBiometricService? = null
    private var isSurfaceReady = false
    private val handler = Handler(Looper.getMainLooper())
    private val productsList = mutableListOf<org.json.JSONObject>()
    private lateinit var adapter: ProductAdapter

    private val purchaseReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.tcs.vehicleassistant.ACTION_PURCHASE") {
                startFaceAuth()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_amazon_shopping)

        rvProducts = findViewById(R.id.rvProducts)
        tvStatus = findViewById(R.id.tvStatus)
        svBiometricPreview = findViewById(R.id.svBiometricPreview)
        tvBiometricStatus = findViewById(R.id.tvBiometricStatus)

        // Setup RecyclerView with Grid Layout
        rvProducts.layoutManager = androidx.recyclerview.widget.GridLayoutManager(this, 2)
        adapter = ProductAdapter(productsList)
        rvProducts.adapter = adapter

        svBiometricPreview.holder.addCallback(this)

        registerReceiver(purchaseReceiver, IntentFilter("com.tcs.vehicleassistant.ACTION_PURCHASE"), Context.RECEIVER_NOT_EXPORTED)

        connectToBiometricService()

        val action = intent.getStringExtra("ACTION")
        val itemName = intent.getStringExtra("ITEM_NAME") ?: "gift"

        if (action == "SEARCH") {
            scrapeData(itemName)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(purchaseReceiver)
        biometricService?.stopBiometricAuthentication()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val action = intent.getStringExtra("ACTION")
        val itemName = intent.getStringExtra("ITEM_NAME")
        if (action == "SEARCH" && itemName != null) {
            scrapeData(itemName)
        }
    }

    private fun connectToBiometricService() {
        try {
            val serviceManagerClass = Class.forName("android.os.ServiceManager")
            val getServiceMethod = serviceManagerClass.getMethod("getService", String::class.java)
            val carServiceBinder = getServiceMethod.invoke(null, "car_service") as IBinder

            val iCarStubClass = Class.forName("android.car.ICar\$Stub")
            val asInterfaceMethod = iCarStubClass.getMethod("asInterface", IBinder::class.java)
            val iCar = asInterfaceMethod.invoke(null, carServiceBinder)

            val getCarServiceMethod = iCar.javaClass.getMethod("getCarService", String::class.java)
            val biometricBinder = getCarServiceMethod.invoke(iCar, "car_biometric") as IBinder

            biometricService = ICarBiometricService.Stub.asInterface(biometricBinder)
            Log.i("AmazonShopping", "Successfully connected to custom ICarBiometricService")
        } catch (e: Exception) {
            Log.e("AmazonShopping", "Failed to connect to car_biometric", e)
        }
    }

    private fun startFaceAuth() {
        tvStatus.text = "Authorizing purchase..."
        svBiometricPreview.visibility = View.VISIBLE
        tvBiometricStatus.visibility = View.VISIBLE

        if (isSurfaceReady) {
            triggerBiometricService()
        }
    }

    private fun triggerBiometricService() {
        try {
            biometricService?.startBiometricAuthentication(svBiometricPreview.holder.surface)
            
            handler.postDelayed({
                biometricService?.stopBiometricAuthentication()
                svBiometricPreview.visibility = View.GONE
                tvBiometricStatus.visibility = View.GONE
                tvStatus.text = "Order Placed! Estimated delivery: Tomorrow by 8 PM"
                tvStatus.setTextColor(android.graphics.Color.GREEN)
            }, 3000)

        } catch (e: Exception) {
            Log.e("AmazonShopping", "Error starting biometrics", e)
            tvStatus.text = "Authentication failed. Error: ${e.message}"
        }
    }

    private fun scrapeData(query: String) {
        tvStatus.text = "Searching Amazon for '$query'..."
        productsList.clear()
        adapter.notifyDataSetChanged()
        
        thread {
            try {
                // Determine category API to give realistic data
                val searchUrl = if (query.contains("jewelry", ignoreCase = true) || query.contains("gift", ignoreCase = true) || query.contains("ring", ignoreCase = true)) {
                    "https://dummyjson.com/products/category/womens-jewellery"
                } else if (query.contains("perfume", ignoreCase = true) || query.contains("fragrance", ignoreCase = true)) {
                    "https://dummyjson.com/products/category/fragrances"
                } else {
                    "https://dummyjson.com/products/search?q=$query"
                }
                
                val dummyUrl = java.net.URL(searchUrl)
                val connection = dummyUrl.openConnection() as java.net.HttpURLConnection
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                connection.connect()
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val json = org.json.JSONObject(response)
                
                if (json.has("products")) {
                    val productsArray = json.getJSONArray("products")
                    val itemsToAdd = mutableListOf<org.json.JSONObject>()
                    
                    for (i in 0 until productsArray.length()) {
                        itemsToAdd.add(productsArray.getJSONObject(i))
                    }
                    
                    if (itemsToAdd.isEmpty()) {
                        // Very basic fallback
                        val fallback = org.json.JSONObject()
                        fallback.put("title", "Amazon Choice $query")
                        fallback.put("price", 29.99)
                        fallback.put("thumbnail", "")
                        itemsToAdd.add(fallback)
                    }

                    runOnUiThread {
                        productsList.addAll(itemsToAdd)
                        adapter.notifyDataSetChanged()
                        tvStatus.text = "Please say 'Buy it' to confirm purchase."
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    tvStatus.text = "Search failed: ${e.message}"
                    Log.e("AmazonShopping", "Exception in scraping thread", e)
                }
            }
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        isSurfaceReady = true
        if (svBiometricPreview.visibility == View.VISIBLE) {
            triggerBiometricService()
        }
    }
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
    override fun surfaceDestroyed(holder: SurfaceHolder) {
        isSurfaceReady = false
    }

    inner class ProductAdapter(private val products: List<org.json.JSONObject>) : 
        androidx.recyclerview.widget.RecyclerView.Adapter<ProductAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : androidx.recyclerview.widget.RecyclerView.ViewHolder(view) {
            val ivThumb: ImageView = view.findViewById(R.id.ivProductThumb)
            val tvTitle: TextView = view.findViewById(R.id.tvItemTitle)
            val tvPrice: TextView = view.findViewById(R.id.tvItemPrice)
        }

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): ViewHolder {
            val view = android.view.LayoutInflater.from(parent.context)
                .inflate(R.layout.item_amazon_product, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val product = products[position]
            holder.tvTitle.text = product.optString("title", "Amazon Product")
            holder.tvPrice.text = "$" + product.optDouble("price", 29.99).toString()
            holder.ivThumb.setImageResource(android.R.color.transparent)
            
            val imageUrl = product.optString("thumbnail", "")
            holder.ivThumb.tag = imageUrl
            
            if (imageUrl.isNotEmpty()) {
                thread {
                    try {
                        val imgConn = java.net.URL(imageUrl).openConnection() as java.net.HttpURLConnection
                        imgConn.connectTimeout = 5000
                        imgConn.readTimeout = 5000
                        imgConn.connect()
                        val bitmap = android.graphics.BitmapFactory.decodeStream(imgConn.inputStream)
                        holder.ivThumb.post {
                            if (holder.ivThumb.tag == imageUrl) {
                                holder.ivThumb.setImageBitmap(bitmap)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("AmazonShopping", "Error loading image", e)
                    }
                }
            }
        }

        override fun getItemCount() = products.size
    }
}
