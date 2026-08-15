package com.kite.demo

import android.os.Bundle
import com.google.android.material.snackbar.Snackbar
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.navigation.findNavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.navigateUp
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.navigation.ui.setupWithNavController
import android.view.Menu
import android.view.MenuItem
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.kite.demo.core.analytics.Analytics
import com.kite.demo.databinding.ActivityMainBinding
import com.kite.demo.ui.CounterViewModel
import com.kite.demo.ui.counterViewModel
import com.kite.di.runtime.android.injected

class MainActivity : AppCompatActivity() {

    private lateinit var appBarConfiguration: AppBarConfiguration
    private lateinit var binding: ActivityMainBinding

    // Resolved against this Activity's scope on first access.
    private val analytics: Analytics by injected()

    // Generated adapter: built from the graph, retained by the ViewModelStore —
    // the count survives rotation.
    private val counter: CounterViewModel by counterViewModel()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        analytics.track("main_activity_opened")
        enableEdgeToEdge()

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        setSupportActionBar(binding.toolbar)

        val navHostFragment =
            supportFragmentManager.findFragmentById(R.id.nav_host_fragment_content_main) as NavHostFragment
        val navController = navHostFragment.navController

        // Bottom-nav destinations are top-level: no Up arrow between tabs. The
        // feature screens (Orders, Profile) are fragments from :feature:*:impl.
        appBarConfiguration = AppBarConfiguration(
            setOf(R.id.FirstFragment, R.id.OrdersFragment, R.id.ProfileFragment),
        )
        setupActionBarWithNavController(navController, appBarConfiguration)
        findViewById<BottomNavigationView>(R.id.bottom_nav).setupWithNavController(navController)

        binding.fab.setOnClickListener { view ->
            Snackbar.make(view, "Clicked ${counter.onFabClick()} times (rotate me!)", Snackbar.LENGTH_LONG)
                .setAnchorView(R.id.fab).show()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // Inflate the menu; this adds items to the action bar if it is present.
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        return when (item.itemId) {
            R.id.action_settings -> true
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        val navController = findNavController(R.id.nav_host_fragment_content_main)
        return navController.navigateUp(appBarConfiguration)
            || super.onSupportNavigateUp()
    }
}