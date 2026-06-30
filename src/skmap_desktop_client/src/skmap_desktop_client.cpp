/*
    Shubh Khandelwal
*/

#define MAX_UDP_PAYLOAD 65507
#define PING_PORT 60000
#define IMU_PORT 60001
#define IMG_PORT 60002

#include <boost/asio.hpp>
#include <charconv>
#include <rclcpp/rclcpp.hpp>
#include <sensor_msgs/msg/compressed_image.hpp>
#include <sensor_msgs/msg/imu.hpp>

class SKMapDesktopClientNode : public rclcpp::Node
{

    private:

        boost::asio::io_context io_context;
        boost::asio::ip::udp::socket ping_socket;
        boost::asio::ip::udp::socket imu_socket;
        boost::asio::ip::udp::socket img_socket;

        rclcpp::Publisher<sensor_msgs::msg::CompressedImage>::SharedPtr image_publisher;
        rclcpp::Publisher<sensor_msgs::msg::Imu>::SharedPtr imu_publisher;

        std::thread ping_thread;
        std::thread imu_thread;
        std::thread img_thread;

        void loopPING()
        {
            boost::asio::ip::udp::endpoint sender_endpoint;
            boost::system::error_code error;
            const char ack[] = "ACK";
            std::vector<uint8_t> buffer(256);
            while (rclcpp::ok())
            {
                size_t len = this->ping_socket.receive_from(boost::asio::buffer(buffer), sender_endpoint, 0, error);
                if ((error == boost::asio::error::operation_aborted) || (error == boost::asio::error::bad_descriptor)) break;
                if (len >= 4 && std::memcmp(buffer.data(), "PING", 4) == 0)
                {
                    boost::system::error_code ec;
                    this->ping_socket.send_to(boost::asio::buffer(ack), sender_endpoint, 0, ec);
                    if (!ec)
                    {
                        RCLCPP_INFO_ONCE(this->get_logger(), "Connection established: %s", sender_endpoint.address().to_string().c_str());
                    }
                }
            }
        }

        void loopIMU()
        {
            boost::asio::ip::udp::endpoint sender_endpoint;
            boost::system::error_code error;
            std::vector<uint8_t> buffer(MAX_UDP_PAYLOAD + 1);
            while (rclcpp::ok())
            {
                size_t len = this->imu_socket.receive_from(boost::asio::buffer(buffer), sender_endpoint, 0, error);
                if ((error == boost::asio::error::operation_aborted) || (error == boost::asio::error::bad_descriptor)) break;
                if (len > 4 && std::memcmp(buffer.data(), "IMU|", 4) == 0)
                {
                    buffer[len] = '\0';
                    std::string_view payload(reinterpret_cast<const char*>(buffer.data()), len);
                    size_t first_pipe = 3;
                    size_t second_pipe = payload.find('|', first_pipe + 1);
                    if (second_pipe != std::string::npos)
                    {
                        try
                        {
                            
                            int64_t ts_ms = 0;
                            auto ts_view = payload.substr(first_pipe + 1, second_pipe - first_pipe - 1);
                            std::from_chars(ts_view.data(), ts_view.data() + ts_view.size(), ts_ms);
                            rclcpp::Time stamp(ts_ms * 1000000LL);

                            const char* ptr = payload.data() + second_pipe + 1;
                            char* next;
                            float ax = std::strtof(ptr, &next); ptr = next + 1;
                            float ay = std::strtof(ptr, &next); ptr = next + 1;
                            float az = std::strtof(ptr, &next); ptr = next + 1;
                            float gx = std::strtof(ptr, &next); ptr = next + 1;
                            float gy = std::strtof(ptr, &next); ptr = next + 1;
                            float gz = std::strtof(ptr, &next);

                            auto imu_msg = sensor_msgs::msg::Imu();
                            imu_msg.header.stamp = stamp;
                            imu_msg.header.frame_id = "base_link";
                            imu_msg.linear_acceleration.x = ax;
                            imu_msg.linear_acceleration.y = ay;
                            imu_msg.linear_acceleration.z = az;
                            imu_msg.angular_velocity.x = gx;
                            imu_msg.angular_velocity.y = gy;
                            imu_msg.angular_velocity.z = gz;
                            imu_msg.orientation_covariance[0] = -1.0;
                            this->imu_publisher->publish(imu_msg);

                        } catch (const std::exception& e)
                        {
                            RCLCPP_WARN_THROTTLE(this->get_logger(), *this->get_clock(), 1000, "IMU parse error: %s", e.what());
                        }
                    }
                }
            }
        }

        void img_loop()
        {
            boost::asio::ip::udp::endpoint sender_endpoint;
            boost::system::error_code error;
            std::vector<uint8_t> buffer(MAX_UDP_PAYLOAD);
            while (rclcpp::ok())
            {
                size_t len = this->img_socket.receive_from(boost::asio::buffer(buffer), sender_endpoint, 0, error);
                if ((error == boost::asio::error::operation_aborted) || (error == boost::asio::error::bad_descriptor)) break;
                if (len > 4 && std::memcmp(buffer.data(), "IMG|", 4) == 0)
                {
                    const void* second_pipe_ptr = std::memchr(buffer.data() + 4, '|', len - 4);
                    if (second_pipe_ptr != nullptr)
                    {
                        size_t second_pipe = static_cast<const uint8_t*>(second_pipe_ptr) - buffer.data();
                        try
                        {

                            int64_t ts_ms = 0;
                            std::from_chars(reinterpret_cast<const char*>(buffer.data() + 4), reinterpret_cast<const char*>(buffer.data() + second_pipe), ts_ms);
                            rclcpp::Time stamp(ts_ms * 1000000LL);

                            auto img_msg = sensor_msgs::msg::CompressedImage();
                            img_msg.header.stamp = stamp; // Using Android hardware time
                            img_msg.header.frame_id = "base_link";
                            img_msg.format = "jpeg";
                            img_msg.data.assign(buffer.data() + second_pipe + 1, buffer.data() + len);
                            this->image_publisher->publish(img_msg);

                        } catch (const std::exception& e)
                        {
                            RCLCPP_WARN_THROTTLE(this->get_logger(), *this->get_clock(), 1000, "IMG parse error: %s", e.what());
                        }
                    }
                }
            }
        }

    public:

        SKMapDesktopClientNode() :
        Node("skmap_desktop_client_node", rclcpp::NodeOptions().automatically_declare_parameters_from_overrides(true)),
        io_context(),
        ping_socket(io_context),
        imu_socket(io_context),
        img_socket(io_context),
        image_publisher(this->create_publisher<sensor_msgs::msg::CompressedImage>(
            "/android/camera/image/compressed",
            rclcpp::QoS(rclcpp::QoSInitialization::from_rmw(rmw_qos_profile_sensor_data))
        )),
        imu_publisher(this->create_publisher<sensor_msgs::msg::Imu>(
            "/android/imu",
            rclcpp::QoS(rclcpp::QoSInitialization::from_rmw(rmw_qos_profile_sensor_data))
        ))
        {

            try
            {

                this->ping_socket.open(boost::asio::ip::udp::v4());
                this->ping_socket.bind(boost::asio::ip::udp::endpoint(boost::asio::ip::udp::v4(), PING_PORT));

                this->imu_socket.open(boost::asio::ip::udp::v4());
                this->imu_socket.bind(boost::asio::ip::udp::endpoint(boost::asio::ip::udp::v4(), IMU_PORT));

                this->img_socket.open(boost::asio::ip::udp::v4());
                this->img_socket.bind(boost::asio::ip::udp::endpoint(boost::asio::ip::udp::v4(), IMG_PORT));

                RCLCPP_INFO(this->get_logger(), "Listening on ports: PING(%d), IMU(%d), IMG(%d)", PING_PORT, IMU_PORT, IMG_PORT);
                
            } catch (const boost::system::system_error& e)
            {
                RCLCPP_FATAL(this->get_logger(), "Failed to bind sockets: %s", e.what());
                throw;
            }

            this->ping_thread = std::thread(&SKMapDesktopClientNode::loopPING, this);
            this->imu_thread = std::thread(&SKMapDesktopClientNode::loopIMU, this);
            this->img_thread = std::thread(&SKMapDesktopClientNode::img_loop, this);

        }

        ~SKMapDesktopClientNode()
        {
            boost::system::error_code ec;
            this->ping_socket.close(ec);
            this->imu_socket.close(ec);
            this->img_socket.close(ec);
            if (this->ping_thread.joinable()) this->ping_thread.join();
            if (this->imu_thread.joinable()) this->imu_thread.join();
            if (this->img_thread.joinable()) this->img_thread.join();
        }

};

int main(int argc, char * argv[])
{
    rclcpp::init(argc, argv);
    rclcpp::spin(std::make_shared<SKMapDesktopClientNode>());
    rclcpp::shutdown();
    return 0;
}